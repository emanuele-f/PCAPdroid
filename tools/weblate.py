#!/usr/bin/env python3
# Copyright 2026 - Emanuele Faranda
#
# Cherry-pick translation updates from Weblate and squash consecutive
# commits by the same author.
#
# Usage:
#   tools/weblate.py status            Show pending translation commits
#   tools/weblate.py update            Update all tracked locales
#   tools/weblate.py update <locale>   Update a single locale (e.g. "ru")

import subprocess
import sys
import os
import re

REMOTE = "weblate"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
STATUS_FILE = os.path.join(SCRIPT_DIR, "weblate_status")
BUILD_GRADLE = os.path.join(REPO_ROOT, "app", "build.gradle")
STRINGS_BASE = "app/src/main/res"

def die(msg):
    print(f"Error: {msg}", file=sys.stderr)
    sys.exit(1)

def git(*args, check=True, stdin=None):
    result = subprocess.run(["git"] + list(args),
                            input=stdin, capture_output=True, text=True)
    if check and (result.returncode != 0):
        die(f"git {' '.join(args)}\n{result.stderr.strip()}")
    return result

def locale_path(locale):
    return f"{STRINGS_BASE}/values-{locale}/strings.xml"

# ---------------------------------------------------------------------------
# Status file: one "locale=commit_hash" per line
# ---------------------------------------------------------------------------

def load_status():
    status = {}
    if os.path.exists(STATUS_FILE):
        with open(STATUS_FILE) as f:
            for line in f:
                line = line.strip()
                if (not line) or line.startswith("#"):
                    continue
                locale, commit = line.split("=", 1)
                status[locale] = commit
    return status

def save_status(status):
    with open(STATUS_FILE, "w") as f:
        for locale in sorted(status):
            f.write(f"{locale}={status[locale]}\n")

# ---------------------------------------------------------------------------
# Git helpers
# ---------------------------------------------------------------------------

def is_on_master(commit):
    """True if *commit* is already reachable from HEAD."""
    return git("merge-base", "--is-ancestor", commit, "HEAD",
               check=False).returncode == 0

def get_pending_commits(locale, last_commit):
    """Commits on weblate/master after *last_commit* that touch *locale*."""
    path = locale_path(locale)
    args = ["log", "--format=%H", "--reverse", "--no-merges"]
    if last_commit:
        args += ["--ancestry-path", f"{last_commit}..{REMOTE}/master"]
    else:
        args.append(f"{REMOTE}/master")
    args += ["--", path]
    out = git(*args).stdout.strip()
    return [h for h in out.split("\n") if h]

def get_commit_info(commit):
    """Return (name, email, date, message) for *commit*."""
    out = git("log", "-1", "--format=%an\n%ae\n%aI\n%s", commit).stdout.strip()
    name, email, date, message = out.split("\n", 3)
    return name, email, date, message

def has_staged_changes():
    return git("diff", "--cached", "--quiet", check=False).returncode != 0

def locale_exists_on_disk(locale):
    return os.path.isdir(os.path.join(STRINGS_BASE, f"values-{locale}"))

def count_strings(content, translatable_only=False):
    """Count <string name= entries in an Android strings XML."""
    if translatable_only:
        return len(re.findall(r'<string name="[^"]*"(?![^>]*translatable="false")', content))
    return len(re.findall(r'<string name="', content))

def translation_pct(locale):
    """Return translation percentage string for a locale on weblate/master."""
    en_path = f"{STRINGS_BASE}/values/strings.xml"
    path = locale_path(locale)
    en_content = git("show", f"{REMOTE}/master:{en_path}").stdout
    locale_result = git("show", f"{REMOTE}/master:{path}", check=False)
    if locale_result.returncode != 0:
        return "0%"
    total = count_strings(en_content, translatable_only=True)
    if total == 0:
        return "0%"
    translated = count_strings(locale_result.stdout)
    return f"{100 * translated // total}%"

def working_tree_clean():
    return git("diff", "--quiet", "--", STRINGS_BASE, check=False).returncode == 0 and \
           git("diff", "--cached", "--quiet", "--", STRINGS_BASE, check=False).returncode == 0

def get_supported_locales():
    """Non-English locales from resourceConfigurations in build.gradle."""
    with open(BUILD_GRADLE) as f:
        content = f.read()
    locales = re.findall(r'"([a-z]{2}(?:-r[A-Z]{2})?)"',
                         content.split("resourceConfigurations")[1].split("]")[0])
    locales = [l for l in locales if l != "en"]
    return sorted(locales)

# ---------------------------------------------------------------------------
# Grouping consecutive commits by author
# ---------------------------------------------------------------------------

def group_by_author(commits):
    """Return list of groups: {name, email, date, commits, message}."""
    groups = []
    for commit in commits:
        name, email, date, message = get_commit_info(commit)
        if groups and (groups[-1]["email"] == email):
            groups[-1]["commits"].append(commit)
            groups[-1]["date"] = date
            groups[-1]["message"] = message
        else:
            groups.append({
                "name": name,
                "email": email,
                "date": date,
                "commits": [commit],
                "message": message,
            })
    return groups

# ---------------------------------------------------------------------------
# Applying translation changes
# ---------------------------------------------------------------------------

def apply_group(group, path):
    """Apply a group's translation changes via 3-way merge.

    Computes a diff spanning from the parent of the first commit to the
    last commit in the group, then applies it with --3way so that
    master-only changes (e.g. removed strings) are preserved.
    """
    first = group["commits"][0]
    last = group["commits"][-1]
    first_parent = git("rev-parse", f"{first}^").stdout.strip()

    patch = git("diff", "--full-index", first_parent, last, "--", path).stdout
    if not patch.strip():
        return

    result = git("apply", "--3way", check=False, stdin=patch)
    if result.returncode != 0:
        die(f"Failed to apply changes for {path} "
            f"({first[:12]}..{last[:12]}):\n{result.stderr.strip()}")

def verify_locale(locale):
    """Verify that the locale file matches the one on weblate/master."""
    path = locale_path(locale)
    weblate_content = git("show", f"{REMOTE}/master:{path}").stdout

    with open(path) as f:
        our_content = f.read()

    if our_content != weblate_content:
        die(f"locale '{locale}' differs from {REMOTE}/master after update — "
            "manual fix required")

# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

def cmd_status():
    git("fetch", REMOTE)
    status = load_status()
    supported = get_supported_locales()

    for locale in supported:
        pct = translation_pct(locale)
        last = status.get(locale)
        if last:
            pending = get_pending_commits(locale, last)
            translation = [c for c in pending if not is_on_master(c)]
            if translation:
                print(f"  {locale} ({pct}): {len(translation)} pending translation commit(s)")
            else:
                print(f"  {locale} ({pct}): up-to-date")
        else:
            pending = get_pending_commits(locale, None)
            if not pending:
                continue
            translation = [c for c in pending if not is_on_master(c)]
            if not translation:
                continue
            if locale_exists_on_disk(locale):
                print(f"  {locale} ({pct}): NOT TRACKED (exists on disk, "
                      f"{len(translation)} commit(s) — add to status file)")
            else:
                print(f"  {locale} ({pct}): NEW ({len(translation)} commit(s))")

def update_locale(locale, status):
    last = status.get(locale)
    path = locale_path(locale)

    if last is None:
        if locale_exists_on_disk(locale):
            die(f"locale '{locale}' exists on disk but is not in the "
                "status file — add it manually first")

    all_pending = get_pending_commits(locale, last)
    if not all_pending:
        print(f"  {locale}: up-to-date")
        return

    pending = [c for c in all_pending if not is_on_master(c)]
    skipped = len(all_pending) - len(pending)

    if not pending:
        print(f"  {locale}: {skipped} commit(s) already in master, nothing to do")
        return

    extra = f" ({skipped} already in master)" if skipped else ""
    print(f"  {locale}: {len(pending)} pending commit(s){extra}")

    locale_dir = os.path.join(STRINGS_BASE, f"values-{locale}")
    os.makedirs(locale_dir, exist_ok=True)

    groups = group_by_author(pending)
    for group in groups:
        apply_group(group, path)
        git("add", path)

        if not has_staged_changes():
            n = len(group["commits"])
            print(f"    Skipping {n} commit(s) by {group['name']} (no diff)")
            continue

        author = f"{group['name']} <{group['email']}>"
        message = group["message"]
        git("commit", f"--author={author}", f"--date={group['date']}", "-m", message)

        n = len(group["commits"])
        squash_note = f" (squashed {n} commits)" if (n > 1) else ""
        print(f"    Committed: {message} by {group['name']}{squash_note}")

    verify_locale(locale)

    # track the last weblate-side commit (not a master commit) so the
    # range for the next run stays on the weblate lineage
    status[locale] = pending[-1]
    save_status(status)
    print(f"  {locale}: done")

def cmd_update(target_locale=None):
    if not working_tree_clean():
        die("working tree has uncommitted changes — commit or stash first")

    git("fetch", REMOTE)
    status = load_status()
    supported = get_supported_locales()

    if target_locale:
        if target_locale not in supported:
            die(f"locale '{target_locale}' is not in resourceConfigurations")
        locales = [target_locale]
    else:
        locales = supported

    for locale in locales:
        update_locale(locale, status)

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def usage():
    print("Usage: weblate.py <status|update> [locale]")
    sys.exit(1)

def main():
    if len(sys.argv) < 2:
        usage()

    cmd = sys.argv[1]

    if cmd == "status":
        cmd_status()
    elif cmd == "update":
        target = sys.argv[2] if (len(sys.argv) > 2) else None
        cmd_update(target)
    else:
        usage()

if __name__ == "__main__":
    main()
