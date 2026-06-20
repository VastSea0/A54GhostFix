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
- Three home-screen widgets: compact, standard, and large
- Quick Settings tile
- Shizuku-independent emergency touch shield
- Guarded Touch: hold Volume Up to authorize a screen tap or swipe
- Keypad control for TV-style navigation without using the touchscreen
- Optional Accessibility Service triggers for Volume Up, Volume Down, and shake gestures
- Samsung Side key launcher shortcut where the device firmware exposes custom Side key apps
- Bilingual interface: English and Turkish
- No root required
- No data wipe
- Open source under the MIT License

### Requirements

- Samsung Galaxy A54 or a compatible Samsung build exposing `SemInputDeviceManagerService`
- Shizuku running and permission granted to this app
- Android developer options are only needed to start Shizuku, depending on your setup
- Accessibility Service only if you want hardware-key or shake triggers

### Important safety note

This is a workaround, not a hardware repair. If ghost touch returns often, the durable fix is still an OEM display/digitizer assembly and inspection of flex cable, connector seating, grounding/shielding, moisture, and frame pressure.

### Trigger notes

- Widgets and the Quick Settings tile run the same Shizuku shell reset used by the main button.
- Volume-key and shake triggers require enabling **A54 Ghost Fix Trigger** in Android Accessibility settings, then turning on the desired triggers inside the app.
- Android does not provide normal apps with a reliable long-press Power key API. The app includes a separate **Run Ghost Fix** launcher activity so Samsung Side key settings can target it on firmware versions that support custom Side key app actions. The in-app Side Key shortcut switch can disable that action without removing the shortcut from Samsung settings.

### Emergency Control

Emergency Control does not need Shizuku, ADB, Wi-Fi debugging, or root after the Accessibility Service has been enabled.

**Guarded Touch**

- All touchscreen input is blocked by a full-screen accessibility overlay.
- Hold Volume Up while tapping or swiping to authorize that interaction.
- Short Volume Down performs Back.
- Long Volume Down performs Home.
- Press both volume keys briefly to enter Keypad mode.
- Hold both volume keys for about two seconds to exit Emergency Control.

**Keypad**

- Volume Up selects the previous visible control.
- Volume Down selects the next visible control.
- Press both volume keys briefly to activate the selected control.
- Long Volume Up performs Back.
- Long Volume Down performs Home.
- Hold both volume keys for about two seconds to exit Emergency Control.

The overlay uses Android accessibility nodes. Standard Android controls, launchers, settings, and most apps work well. Games, protected surfaces, and custom canvas interfaces may expose few or no selectable controls.

### Live diagnosis evidence

The connected SM-A546E produced an unsolicited gesture directly from `/dev/input/event5` (`sec_touchscreen`) while the screen was untouched. Samsung factory diagnostics also reported:

```text
TRANS_RAW: NG
FULL_RAWCAP: NG
FULL_NOISE: NG
ABS_RAW: NG
ABS_NOISE: NG
System firmware binary: SY32010016
Touch IC version query: SY320100FF
Force calibration: NA
```

This strongly indicates a digitizer/controller, grounding, connection, or panel compatibility fault. No touch firmware update was performed because the installed aftermarket controller could become unusable and no rollback image is available.

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
- Üç ana ekran widget’ı: kompakt, standart ve büyük
- Hızlı Ayarlar kutucuğu
- Shizuku’dan bağımsız acil dokunma kalkanı
- Korumalı Dokunma: ekran dokunuşunu onaylamak için Ses Açma tuşunu basılı tutma
- Dokunmatik kullanmadan TV tarzı Tuş Takımı kontrolü
- Ses Açma, Ses Kısma ve çalkalama için isteğe bağlı Erişilebilirlik Servisi tetikleyicileri
- Cihaz yazılımı özel Yan tuş uygulamalarını destekliyorsa Samsung Yan tuş başlatma kısayolu
- İki dilli arayüz: Türkçe ve İngilizce
- Root gerektirmez
- Veri silmez
- MIT lisanslı açık kaynak

### Gereksinimler

- `SemInputDeviceManagerService` sunan Samsung Galaxy A54 veya uyumlu Samsung yazılımı
- Shizuku çalışıyor olmalı ve bu uygulamaya izin verilmiş olmalı
- Kurulum şekline göre Shizuku’yu başlatmak için geliştirici seçenekleri gerekebilir
- Telefon tuşu veya çalkalama tetikleyicileri için Erişilebilirlik Servisi gerekir

### Önemli güvenlik notu

Bu bir geçici çözüm, donanım onarımı değildir. Ghost touch sık sık geri dönüyorsa kalıcı çözüm hâlâ OEM ekran/digitizer modülü ve flex kablosu, konnektör oturması, grounding/shielding, nem ve kasa baskısı kontrolüdür.

### Tetikleyici notları

- Widget’lar ve Hızlı Ayarlar kutucuğu, ana butonla aynı Shizuku shell resetini çalıştırır.
- Ses tuşu ve çalkalama tetikleyicileri için Android Erişilebilirlik ayarlarından **A54 Ghost Fix Tetikleyici** servisini açıp, uygulama içinden istediğin tetikleyicileri etkinleştir.
- Android normal uygulamalara güvenilir bir güç tuşu uzun basış API’si vermez. Uygulamada ayrı bir **Ghost Fix Çalıştır** launcher aktivitesi var; Samsung Yan tuş ayarları özel uygulama eylemini destekleyen yazılımlarda bunu hedefleyebilir. Uygulama içindeki Yan Tuş kısayolu anahtarı, Samsung ayarlarından kısayolu silmeden bu eylemi kapatabilir.

### Acil Durum Kontrolü

Erişilebilirlik Servisi bir kez açıldıktan sonra Acil Durum Kontrolü Shizuku, ADB, Wi-Fi debugging veya root gerektirmez.

**Korumalı Dokunma**

- Tam ekran erişilebilirlik overlay’i bütün dokunmatik girişlerini engeller.
- Bir tap veya swipe hareketini onaylamak için Ses Açma tuşunu basılı tut.
- Ses Kısma kısa basış Geri işlemini yapar.
- Ses Kısma uzun basış Ana Ekran işlemini yapar.
- İki ses tuşuna kısa basarak Tuş Takımı moduna geç.
- Acil Durum Kontrolü’nden çıkmak için iki ses tuşunu yaklaşık iki saniye basılı tut.

**Tuş Takımı**

- Ses Açma önceki görünür kontrolü seçer.
- Ses Kısma sonraki görünür kontrolü seçer.
- Seçili kontrolü çalıştırmak için iki ses tuşuna kısa bas.
- Ses Açma uzun basış Geri işlemini yapar.
- Ses Kısma uzun basış Ana Ekran işlemini yapar.
- Acil Durum Kontrolü’nden çıkmak için iki ses tuşunu yaklaşık iki saniye basılı tut.

Overlay Android erişilebilirlik düğümlerini kullanır. Standart Android kontrolleri, launcher, ayarlar ve çoğu uygulama iyi çalışır. Oyunlar, korumalı yüzeyler ve özel canvas arayüzleri az sayıda veya hiç seçilebilir kontrol sunmayabilir.

### Canlı tanı kanıtları

Bağlı SM-A546E, ekrana dokunulmadığı sırada doğrudan `/dev/input/event5` (`sec_touchscreen`) üzerinden kendiliğinden bir kaydırma hareketi üretti. Samsung fabrika testleri de şunları bildirdi:

```text
TRANS_RAW: NG
FULL_RAWCAP: NG
FULL_NOISE: NG
ABS_RAW: NG
ABS_NOISE: NG
Sistem firmware binary: SY32010016
Dokunmatik IC sürüm sorgusu: SY320100FF
Force calibration: NA
```

Bu sonuç digitizer/kontrolcü, grounding, bağlantı veya panel uyumluluğu arızasını güçlü biçimde gösteriyor. Yan sanayi kontrolcü kullanılamaz hale gelebileceği ve geri dönüş imajı olmadığı için touch firmware güncellemesi yapılmadı.

## Build

```sh
./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```
