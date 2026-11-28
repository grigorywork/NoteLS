## В работе89 — кандидат1.10.63/code86,28ноября2026

Настройка «Часы и дни в таймерах» defaulttrue, выключение показывает суммарные минуты:секунды. Под часом MM:SS, при60мин1ч00м, при24ч1д00ч00м. Полный вид хранит ненулевые секунды, компактный при>=1ч оставляет минуты; полный accessibility-label точен. RU/EN, без Locale.setDefault/переполнения formatter, сырые миллисекунды/даты/Room19 не меняются. TaskTimer.format и формат аудиозаписей остаются прежними.

Один event-driven/lifecycle settings subscription на themed root через composition local, не на карточку/тик. Новый formatter используется секундомером/обратным отсчётом/аналитикой. Новых таймеров/фоновыхjobs/wakelock/периодическогоопроса нет; существующий foreground visible clock не менялся. Уведомление идущей задачи относится к следующему90, его ещё нет.

Подготовлены4pure/2prefs/3NativeUI=9новыхрегрессий; expectedfull914НЕpass. ЛокальныхSDKGradlebuildsнет, пока компиляция/тестыНЕподтверждены. Featurecodex/duration-1.10.63; baselinee571a62/verified62. Mainapp62проверено,63НЕinstaller/89НЕзакрыт. Послеtarget/аудит→fullCI/lint/APK→autoSigned/independentAPKactualRoom19→freshreleaseGC,только потом90–97.

ПослеВСЕХновые3hаудита→3hоптимизацииоба0ms/неначаты,паузы/CIqueue/ожидание/простойисключать. Батареяобязательна,68/iPhone/новыеИИне начинать/подписьне менять. App/testприCIзаморожены,RESTforce=false,не дублироватьживуюработу. Ниже история.


Примеры:1ч02м/1ч 02м 03с;1д00ч00м/1440:00. Настройкаразмещенавкомпактнойгруппередакторасpreview. Проверкиboundary59:59→1ч,23:59:59→день/LongMINMAX/ENlocale/prefsreload-corrupt/UIclocks+analytics/сохранениесырыхданных.
