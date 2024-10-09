To provide a consistent user experience, languages with missing translations are excluded from the app build.
Usually this means languages with 5% or more untranslated strings. Such languages may still be present in the resources folder, however they won't be usable by the users.

The languages currently included into the app need to be specified in 2 places:

- `resourceConfigurations` in `app/build.gradle`: specifies the folder names of the actual translations to include
- `app/src/main/res/xml/locales_config.xml`: specifies the locales which are displayed in the Android own language selector

Note: the locale name in `locales_config.xml` can differ from the language specified in `resourceConfigurations`, see
https://developer.android.com/guide/topics/resources/app-languages#sample-config for some examples

## Adding a new language

Here is a summary of the steps needed to add a new language:

1. The language translation first needs to be completed on [Weblate](https://hosted.weblate.org/projects/pcapdroid)
2. The language related commits are cherry-picked to `master`, and possibly squashed
3. `build.gradle` and `locales_config.xml` is updated as explained about
