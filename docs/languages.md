To provide a consistent user experience, languages with missing translations are excluded from the app build.
Usually this means languages with 5% or more untranslated strings. Such languages may still be present in the resources folder, however they won't be usable by the users.

The languages currently included into the app need to be specified in 2 places:

- `resourceConfigurations` in `app/build.gradle`: specifies the folder names of the actual translations to include
- `app/src/main/res/xml/locales_config.xml`: specifies the locales which are displayed in the Android own language selector

Note: the locale name in `locales_config.xml` can differ from the language specified in `resourceConfigurations`, see
https://developer.android.com/guide/topics/resources/app-languages#sample-config for some examples

## Updating translations

The `tools/weblate.py` script automates cherry-picking translation commits from the `weblate` remote.
It assumes a `weblate` git remote is configured:

```
git remote add weblate https://hosted.weblate.org/git/pcapdroid/app
```

The correct way to update translations is:

1. Merge origin into weblate
2. Git fetch weblate
3. run the locale update script and push the commits
4. Merge origin into weblate again

The script tracks the last cherry-picked commit for each locale in `tools/weblate_status`.
Only locales listed in `resourceConfigurations` in `app/build.gradle` are considered.

Available commands:

- `tools/weblate.py status` — show which locales have pending translation commits
- `tools/weblate.py update` — cherry-pick pending commits for all supported locales
- `tools/weblate.py update <locale>` — cherry-pick pending commits for a single locale (e.g. `ru`)

Consecutive commits by the same author are automatically squashed into one.
After processing a locale, the script verifies that the local file matches `weblate/master`; if not, it exits with an error for manual resolution.

## Adding a new language

Here is a summary of the steps needed to add a new language:

1. The language translation first needs to be completed on [Weblate](https://hosted.weblate.org/projects/pcapdroid)
2. `build.gradle` and `locales_config.xml` are updated as explained above
3. Run `tools/weblate.py update <locale>` to cherry-pick the translation commits
