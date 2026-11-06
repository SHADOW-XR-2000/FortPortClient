# FortPortClient — Android-клиент для fortport.ru

Проект Android Studio (Kotlin, Gradle) - впроинципе собирается за минуту.

## Обновления

Приложение само проверяет обновления в GitHub Releases этого репозитория (`UpdateManager.kt`):
при запуске (не чаще раза в 6 часов) и по кнопке **Настройки → Кастомизация → Обновления → Проверить обновления**.
Если вышла новая сборка — показывается окно с описанием, затем APK скачивается и открывается системный установщик.

**Как выпускается обновление.** Workflow `Release APK` (`.github/workflows/release.yml`) на каждый push в `main`/`master`
(и после `Import project from zip`) собирает подписанный APK и создаёт релиз `build-N`, где N — номер запуска (он же `versionCode`).
Вручную: Actions → Release APK → Run workflow; в поле «notes» можно написать, что нового (это текст из окна обновления).
Хранятся последние 10 релизов.

**Один раз настроить подпись.** Android ставит обновление поверх приложения только если APK подписан тем же ключом,
поэтому ключ должен быть постоянным. Создайте его (на любом компьютере с Java):

```
keytool -genkeypair -v -keystore fortport-release.jks -alias fortport -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 fortport-release.jks     # (на Windows: certutil -encode или любой base64-кодировщик)
```

и добавьте в репозитории Settings → Secrets and variables → Actions четыре секрета:
`SIGNING_KEYSTORE_BASE64` (вывод base64), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (`fortport`), `SIGNING_KEY_PASSWORD`.
Файл `.jks` и пароли храните в надёжном месте: потеряете ключ — обновлять придётся переустановкой.

Первую версию с механизмом обновления (и перейти с debug-сборок из `Build APK`) нужно один раз поставить вручную из
Releases, предварительно удалив старую: подпись у неё другая. Дальше всё обновляется из приложения.
