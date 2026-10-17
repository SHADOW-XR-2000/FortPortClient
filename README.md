# FortPortClient — Android-клиент для fortport.ru

Проект Android Studio (Kotlin, Gradle). Собрать `.apk` в этой среде невозможно
(нет Android SDK и доступа к репозиториям Google), поэтому проект нужно
открыть и собрать самостоятельно — это делается за пару минут.

## Что уже реализовано

- **WebView-обёртка сайта** fortport.ru с сохранением сессии (куки), поддержкой
  масштабирования, кнопкой "назад" по системной кнопке.
- **Тёмная тема** — приложение и содержимое страницы подстраиваются под
  системную тему устройства (`Theme.MaterialComponents.DayNight` +
  `WebSettingsCompat.setAlgorithmicDarkeningAllowed`).
- **Офлайн-кэш** — при отсутствии сети WebView пытается показать последнюю
  сохранённую версию страницы из HTTP-кэша и выводит экран "Нет подключения"
  с кнопкой "Повторить". Кэш работает автоматически, если сайт не отправляет
  заголовки `no-store` для своих ресурсов.
- **Загрузка файлов** — обработка `<input type="file">` на странице: выбор
  файла/фото из галереи или съёмка новой фотографии с камеры (для аватарок,
  вложений к постам и т.п.), включая запрос разрешения на камеру.
- **Локальный кэш иконок и статики интерфейса** (`AssetCache.kt`) — файлы из
  `/ui/`, `/static/`, `/assets/` и по расширению (`.webp`, `.png`, `.svg`,
  шрифты и т.п.) скачиваются один раз и переиспользуются с диска при каждом
  следующем запуске, независимо от заголовков кэширования на сервере. Лимит —
  30 МБ, при превышении удаляются самые старые файлы. Очистить кэш вручную:
  `AssetCache.clear(cacheDir)` (например, повесить на пункт меню «Очистить
  кэш», если понадобится).
- **Встроенные ассеты** (`BundledAssets.kt`, папка `app/src/main/assets/ui/`) —
  часть иконок и лого зашиты прямо в APK и доступны с первого запуска даже
  без интернета (14 файлов, ~584 КБ на момент сборки: иконки ленты, добавления
  поста, лого сайта). Путь внутри `assets/` повторяет путь на сайте, например
  `assets/ui/icons/play.webp` соответствует `https://fortport.ru/ui/icons/play.webp`.
  При запросе такого URL приложение сначала проверяет `assets/`, и только если
  файла там нет — идёт в дисковый кэш/сеть.

  **Как добавить ещё файлов (например, анимации маскота из `/ui/mascot/`):**
  1. В Chrome/Edge откройте нужную страницу сайта → DevTools (F12) → вкладка
     Network → включите "Preserve log" → обновите страницу и, если нужно,
     выполните действия, которые подгружают недостающие ассеты (например,
     наведите курсор на аватар, чтобы появился маскот).
  2. Правой кнопкой по списку запросов → **Save all as HAR with content**
     (важно — именно "with content", иначе бинарные данные файлов не попадут
     в архив).
  3. Пришлите получившийся `.har` — я вытащу из него нужные файлы (они лежат
     там в base64) и добавлю в `assets/ui/...` тем же способом.
- **Нижняя панель навигации** (нативная, не часть вёрстки сайта): Музыка
  (`/music`), Порты/сообщества (`/communities`), центральная кнопка «Добавить
  пост» (вызывает существующую на сайте `window.openPostCreator()`, при
  необходимости сначала переходя на `/main`), Друзья (`/friends`), Переписки
  (`/chats`). Иконки — те же файлы, что уже лежат в `assets/ui/icons`.
  При скролле ленты вниз панель прячется, при скролле вверх — появляется
  снова (слежение за скроллом сделано через `document.addEventListener`
  в фазе capture, поэтому работает независимо от того, скроллится ли вся
  страница целиком или внутренний контейнер ленты).
- **Очистка верхних служебных элементов сайта**: скрыта тёмная строка
  `#utility-bar` (Настройки/Поддержка/О нас/Конфиденциальность/Условия вверху
  страницы), а её пункты (кроме «Настройки» — он там уже есть) добавлены в
  выпадающее меню у аватарки (`#user-dropdown`), рядом с «Мой профиль» и
  «Выйти».
- **Нативная верхняя панель** — замена `#main-top-bar` сайта (скрыт через
  `injectNavCleanup()`): Домой → `/main`; CD-Coins (баланс через `/api/payments/balance`, покупка через `/api/payments/options` и `/api/payments/create` с переходом на confirmationUrl)
  placeholder — см. ниже); Уведомления (колокольчик с бейджем, число
  подтягивается из `.user-notifs .notification-badge`, которое сам сайт
  считает в `updateNotificationBadge()`; по тапу вызывается родная рабочая
  функция `openNotificationsPanel()` — она добавляет панель прямо в
  `document.body`, поэтому видна, даже когда шапка сайта скрыта); Профиль —
  аватар подтягивается из `localStorage.userAvatar` и грузится в фоне, тап
  открывает нативное меню (`PopupMenu`) с теми же пунктами, что и в
  `#user-dropdown` сайта (Профиль/Настройки/Поддержка/О нас/Конфиденциальность/
  Условия/Выйти — «Выйти» дёргает родную `logout()`). Каждая группа — в рамке
  с тёмной заливкой и чёрной обводкой: у кнопки «Домой» углы острые
  (`nav_icon_frame.xml`), у остальных — слегка скруглённые
  (`nav_icon_frame_rounded.xml`). Цвет самой панели — `fp_topbar_bg`: в тёмной
  теме как раньше (#0F1626), в светлой — #009DFF.

  **Примечание:** покупка CD-Coins открывает страницу платёжного провайдера внутри WebView; итоговое зачисление зависит от подтверждения платежа на стороне сервера.
  баланса грузится отдельным скриптом `/scripts/payments/payment-widget.js`,
  который ещё не исследован (его нет ни в одном из присланных HAR). Как
  только пришлёте HAR с его загрузкой (зайти на страницу с виджетом, который
  обычно появляется после логина, и сохранить HAR тем же способом, что и для
  иконок/маскота) — подключу реальный баланс и правильную ссылку.
- **Плашка «Не удалось подключиться к серверу»** — показывается не только
  при отсутствии сети на устройстве, но и когда сеть есть, а сам сервер не
  отвечает (`onReceivedError`/`onReceivedHttpError` для главного фрейма), так
  что пользователь никогда не видит браузерную страницу ошибки. Картинка —
  `assets/ui/logos/net_failconnect_day.png` (светлая тема) или
  `net_failconnect_dark.png` (тёмная), подбирается автоматически по
  `Configuration.UI_MODE_NIGHT_MASK`, ниже заголовок и кнопка «Обновить».
- **Нативный экран диалога** (`ChatActivity.kt`): когда на `/chats` открывается
  диалог (сайт ставит `body.mobile-chat-open` и `?chatId=…`), приложение
  закрывает веб-диалог и открывает нативный экран на весь экран, без панелей.
  Работает с тем же API, что и сайт: история (по 60 сообщений, подгрузка вверх),
  long-poll `/api/chats/{id}/wait`, отправка (текст, ответ на сообщение, файлы),
  правка/удаление своих сообщений, отметка «прочитано», просмотр фото через
  `PhotoViewerActivity`. Тексты шифруются/расшифровываются так же, как на сайте
  (AES-GCM, ключ = SHA-256 от chatId). Веб-версия чата в приложении не показывается;
  вернуться к списку — стрелка «←» или системная «назад».
- **Pull-to-refresh** и индикатор загрузки страницы.
- Внешние ссылки (не на fortport.ru) открываются в обычном браузере, а не
  внутри приложения.

## Сборка

1. Установите [Android Studio](https://developer.android.com/studio) (последняя стабильная версия).
2. Откройте папку `FortPortClient` через **File → Open**.
3. Дождитесь синхронизации Gradle (Android Studio сама скачает нужную версию
   Gradle и Android SDK по `gradle-wrapper.properties`).
4. Нажмите **Run** ▶ на подключённом устройстве/эмуляторе, либо
   **Build → Generate Signed Bundle / APK** для получения `.apk`.

Минимальная версия Android — 8.0 (API 26).

## Push-уведомления

Настоящие push-уведомления с сайта технически возможны только одним из двух способов:

1. **Через Firebase Cloud Messaging (FCM)** — но для этого нужно, чтобы
   *сервер* fortport.ru сам отправлял push-сообщения на токены устройств
   (т.е. на сайте должна быть серверная интеграция с FCM/APNs). Обычные сайты
   этого не делают "из коробки" — такая интеграция обычно есть только у
   официального мобильного приложения. Если у вас нет доступа к бэкенду сайта
   или API push-уведомлений от его владельцев, реальные push от сайта
   получить не получится, что бы ни делало Android-приложение.
2. **Через Web Push самого браузера/WebView** — если сайт использует
   стандартный Web Push API (Service Worker + Push API) для уведомлений в
   браузере, WebView на Android **не поддерживает** Web Push так, как это
   делает Chrome. Это ограничение платформы Android WebView, а не этого
   проекта.

Ниже — готовый каркас на случай, если у вас **есть возможность** подключить
собственный Firebase-проект и отправлять тестовые/собственные уведомления
(например, свои локальные оповещения, не связанные с самим сайтом):

1. Создайте проект на [console.firebase.google.com](https://console.firebase.google.com),
   добавьте Android-приложение с `applicationId = ru.fortport.client`.
2. Скачайте `google-services.json` и положите его в папку `app/`.
3. В `build.gradle.kts` (корень) раскомментируйте строку с плагином
   `com.google.gms.google-services`.
4. В `app/build.gradle.kts` раскомментируйте плагин и зависимости
   `firebase-bom` / `firebase-messaging-ktx`.
5. Создайте файл `app/src/main/java/ru/fortport/client/FortPortMessagingService.kt`:

```kotlin
package ru.fortport.client

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FortPortMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: getString(R.string.app_name)
        val body = message.notification?.body ?: ""

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FortPortApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        getSystemService(NotificationManager::class.java)
            ?.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onNewToken(token: String) {
        // Отправьте token на свой сервер, если планируете рассылать
        // собственные уведомления через Firebase Admin SDK / HTTP v1 API.
    }
}
```

6. Раскомментируйте `<service android:name=".FortPortMessagingService" .../>`
   в `AndroidManifest.xml`.

## Возможные доработки

- Кнопка "Поделиться" для текущей страницы (`Intent.ACTION_SEND`).
- Полноценный офлайн-режим через `Service Worker`/AppCache самого сайта —
  зависит от того, как устроен фронтенд fortport.ru.
- Deep links (`fortport.ru/...` → открытие сразу в приложении по клику из
  других приложений) — добавляется через `intent-filter` с `autoVerify` и
  Digital Asset Links на стороне сайта.

## Правовая оговорка

Это неофициальный клиент. Если вы не являетесь владельцем/администратором
fortport.ru, перед публикацией приложения в Google Play уточните у владельцев
сайта, что использование их контента таким образом допустимо.
