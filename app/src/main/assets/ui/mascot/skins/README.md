# Скины маскота: реплики

Один скин = папка `skins/<id>/` с подпапкой `phrases/`. Каждый файл в `phrases/` — одна категория реплик:
JSON-массив строк, **одна реплика на строку**. Имя файла (без `.json`) и есть ключ категории.

Скину не нужно переопределять всё: категорию, которой в скине нет, берут из `default`.
Чтобы убрать категорию целиком — положите пустой массив `[]`.

## Категории
| файл | когда |
|---|---|
| greetings | первый клик на странице |
| poke | реакция на тычок/поглаживание |
| random | случайные реплики по клику |
| own_profile / profile | свой / чужой профиль (по порядку, со статистикой) |
| port / fortport | страница порта / порт ФортПорта |
| hh_feed, hh_communities, hh_community, hh_community_settings, hh_profile, hh_own_profile, hh_settings, hh_new_community | подсказки «hand holding» по страницам |
| hh_whatis_port | ветка «что такое порты» |

## Подстановки
`${username}` `${pagetitle}` `${postCount}` `${likeCount}` `${dislikeCount}` `${commentCount}` `${topCommunity}` `${totalusers}` `${totalports}`

В подсказках можно задать два выбора кнопок: `${choiceOne=`текст`} ${choiceTwo=`текст`}` в конце реплики.

## Эмоции
Префикс в начале реплики: `/E0/` peek, `/E1/` lookout, `/E2/` wave, `/E3/` killed, `/E4/` stand, `/E5/` think, `/E6/` blush, `/E7/` yell.
Пример: `"/E6/Я ЛЮБЛЮ ФОРТПОРТ!!"`

## Свои скины
Положите папку скина в `files/mascots/<название>/phrases/` на устройстве (или в `assets/ui/mascot/skins/<id>/` для встроенного).
Название папки должно совпадать с выбранным в настройках маскотом.
