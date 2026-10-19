# wahrwelt A/D

![wahrwelt](components/farm/server/front/public/logo.jpg)

Инструменты команды для A/D. Основной стенд: Linux x86_64 и Docker Compose.

При сборке используются обновляемые теги образов и актуальные совместимые
библиотеки. Для Python достаточно коротких `requirements.txt` с `>=`.
Java-образ остается на линии 27, чтобы собирать Packmate.
Farm и Neo используют плавающий образ `python:alpine`; в проверенной сборке
от 2026-10-14 он содержал Python 3.15.0. Локальный сканер запускается системным
Python и не требует внешних библиотек.

| Инструмент | Назначение                                                         |
| ---------- | ------------------------------------------------------------------ |
| Сканер     | Проверка локальных исходников и рецепты исправлений.               |
| S4DFarm    | Прием, учет и отправка флагов.                                     |
| Neo        | Распределение заданий между исполнителями и передача флагов ферме. |
| Packmate   | Просмотр потоков, поиск по трафику и шаблоны подсветки.            |

## Запуск

```bash
./control init           # Создать .env с паролями и ключами.
# Впишите адреса команд в targets.txt, при необходимости поправьте .env.
./control all-up         # Farm, Neo с исполнителем и Packmate сразу.
```

Отдельные компоненты: `./control farm`, `./control neo`, `./control pack`.
После `all-up` отправка флагов в систему соревнования еще выключена: включите ее
командой `./control farm submit-up`, когда настроите `FARM_SYSTEM_*` в `.env`.

`init` создает приватный `.env` и рабочие каталоги, контейнеры не запускает.
Повторный `init` сохраняет существующие пароли.
По умолчанию веб-интерфейсы доступны только на этом компьютере: Farm на порту
8080, Packmate на 65000. Neo управляется через CLI и слушает gRPC на порту 5005.
Для доступа команды задайте
`BIND_ADDRESS` равным LAN-адресу хоста.

Остановка сохраняет данные. Отправка в систему соревнования включается отдельно:

```bash
./control pack down
./control farm restart
./control neo logs
./control status
./control farm submit-up    # Включить отправку после настройки .env.
./control farm submit-down
./control all-down
```

У каждого компонента есть `up`, `down`, `restart`, `logs` и `status`.
`./control --help` показывает справку. Равнозначный запуск: `python3 control.py`.

## От скрипта до борды

Впишите адреса команд в `targets.txt`, а в `.env` настройте формат флага `FARM_FLAG_FORMAT`
и параметры системы приема `FARM_SYSTEM_*`. Затем запустите Farm и Neo:

```bash
./control farm
./control neo
```

Полученный скрипт сначала проверьте на разрешенном адресе. `test` покажет вывод,
код завершения и найденные флаги без их отправки. Для автоматической работы
загрузите скрипт, включите его и просмотрите журнал:

```bash
./control neo test ./sploit_example.py 192.0.2.10 --timeout 10s
./control neo add ./sploit_example.py --id example
./control neo enable example
./control neo tail example
```

Повторный `neo add` с тем же `--id` загружает новую версию скрипта.
Neo запускает задание по адресам из Farm и передает найденные флаги в Farm.
Готовый флаг можно положить в Farm напрямую:

```bash
./control farm flag 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
```

То же действие есть в веб-интерфейсе Farm. Флаги появятся в очереди и таблице.
После настройки протокола, адреса и токена системы соревнования включите отправку:

```bash
./control farm submit-up
```

Статусы ответов системы видны в Farm. Если скрипту нужны соседние файлы,
укажите `--dir` в `neo test` и `neo add`.
В корне лежит [sploit_example.py](sploit_example.py): он получает адрес цели первым аргументом,
делает HTTP-запрос и печатает ответ. Флаги по `FARM_FLAG_FORMAT` ищет сам Neo.
Настройте порт и путь в начале файла, затем добавьте логику своего задания
в `run_exploit`. Neo передает найденные флаги в Farm; отправка на борду
включается отдельно командой `./control farm submit-up`. Все команды и флаги Neo
описаны в [Neo](components/neo/README.md). Подробности: [Farm](components/farm/README.md), [Packmate и LIVE](components/packmate/README.md).

## Проверка исходников

```bash
./control scan /путь/к/проекту --patches
./control scan-external /путь/к/проекту
```

Встроенный сканер запускается системным Python без установки библиотек. Он не запускает проверяемый код. Подробности: [справка по сканерам](docs/scanners.md), [каталог правил](docs/vulnerabilities/README.md).

Проверки сканера:

```bash
python3 -m unittest discover -s tests
python3 scripts/scanner_coverage.py --check
```

Происхождение исходников и уведомления MIT собраны в [LICENSE](LICENSE).
