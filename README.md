# A54 Ghost Fix

Emergency ghost-touch reset for Samsung Galaxy A54 devices affected by aftermarket display or digitizer issues.

## English

### What problem does it solve?

Some Galaxy A54 units start producing ghost touches after a drop and a non-OEM display replacement. In the diagnosed case, Android received false touches directly from the kernel input device:

```text
/dev/input/event5 = sec_touchscreen
ABS_MT_POSITION_X / ABS_MT_POSITION_Y
ABS_MT_TRACKING_ID
```

Because Android treats those events as real touch input, app-level cancellation is not enough. Rebooting clears the condition temporarily, but it is slow and inconvenient.

### What does the app do?

A54 Ghost Fix uses Shizuku to run the same Samsung TSP reset command that works from ADB/aShell:

```sh
service call SemInputDeviceManagerService 30 i32 1 i32 1 s16 module_off_master
sleep 2
service call SemInputDeviceManagerService 30 i32 1 i32 1 s16 module_on_master
```

This turns the touch module off and back on without rebooting the phone.

### Features

- One-tap fix inside the app
- Home-screen widget
- Quick Settings tile
- Bilingual interface: English and Turkish
- No root required
- No data wipe
- Open source under the MIT License

### Requirements

- Samsung Galaxy A54 or a compatible Samsung build exposing `SemInputDeviceManagerService`
- Shizuku running and permission granted to this app
- Android developer options are only needed to start Shizuku, depending on your setup

### Important safety note

This is a workaround, not a hardware repair. If ghost touch returns often, the durable fix is still an OEM display/digitizer assembly and inspection of flex cable, connector seating, grounding/shielding, moisture, and frame pressure.

## Türkçe

### Hangi sorunu çözer?

Bazı Galaxy A54 cihazlarda düşme ve yan sanayi ekran değişimi sonrasında ghost touch başlayabiliyor. Tanılanan vakada Android sahte dokunmaları doğrudan kernel input cihazından alıyordu:

```text
/dev/input/event5 = sec_touchscreen
ABS_MT_POSITION_X / ABS_MT_POSITION_Y
ABS_MT_TRACKING_ID
```

Android bu olayları gerçek dokunma olarak gördüğü için uygulama seviyesinde iptal etmek yeterli olmuyor. Yeniden başlatma durumu geçici olarak temizliyor, fakat yavaş ve pratik değil.

### Uygulama ne yapar?

A54 Ghost Fix, Shizuku kullanarak ADB/aShell içinde çalışan aynı Samsung TSP reset komutunu çalıştırır:

```sh
service call SemInputDeviceManagerService 30 i32 1 i32 1 s16 module_off_master
sleep 2
service call SemInputDeviceManagerService 30 i32 1 i32 1 s16 module_on_master
```

Bu işlem telefonu yeniden başlatmadan dokunmatik modülü kapatıp tekrar açar.

### Özellikler

- Uygulama içinde tek dokunuşla düzeltme
- Ana ekran widget’ı
- Hızlı Ayarlar kutucuğu
- İki dilli arayüz: Türkçe ve İngilizce
- Root gerektirmez
- Veri silmez
- MIT lisanslı açık kaynak

### Gereksinimler

- `SemInputDeviceManagerService` sunan Samsung Galaxy A54 veya uyumlu Samsung yazılımı
- Shizuku çalışıyor olmalı ve bu uygulamaya izin verilmiş olmalı
- Kurulum şekline göre Shizuku’yu başlatmak için geliştirici seçenekleri gerekebilir

### Önemli güvenlik notu

Bu bir geçici çözüm, donanım onarımı değildir. Ghost touch sık sık geri dönüyorsa kalıcı çözüm hâlâ OEM ekran/digitizer modülü ve flex kablosu, konnektör oturması, grounding/shielding, nem ve kasa baskısı kontrolüdür.

## Build

```sh
./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```
