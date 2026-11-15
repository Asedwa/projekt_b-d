# projekt_b-d 🎧

Deezer kitaplığını ve çalma listelerini kişiselleştirilebilir bir oynatıcıda bir araya getiren Android müzik uygulaması.

## APK indir

[**Güncel Android APK’sını indir**](https://github.com/Asedwa/projekt_b-d/releases/latest)

Açılan sayfada **Assets** bölümünden `projekt_b-d-<sürüm>.apk` dosyasını seç. Depo özel olduğu için GitHub hesabınla giriş yapman gerekir. APK, uygulamanın debug derlemesidir.

## 0.9 sürüm notu

Eksik ve yerel parça kapakları için otomatik yedek kapak çözümü ile gömülü ses dosyası kapak okuyucusu eklendi; sade arayüz (`calm` mod) yüzey, kenarlık ve vurgu renkleri iyileştirildi.

## 0.8 sürüm notu

Breeze tarzı sade ve nötr koyu arayüz varsayılan hale getirildi: renkli zemin gradyanları, kart kenarlıkları, daire içi ikon kapları ve kart üstü pin rozetleri kaldırıldı; vurgu rengi yalnızca ana aksiyon, oynatma/ilerleme ve aktif sekmeye indirildi. Çalan şarkı kapağından Palette ile çekilen dinamik renkler artık MiniPlayer ve tam ekran oynatıcıya özel olarak uygulanıyor. Önceki görünüm için Ayarlar'a **Eski Mod** seçeneği eklendi.

## 0.7 sürüm notu

Kilit ekranındaki ileri/geri şarkı kontrolleri düzeltildi. Ekran kapalıyken otomatik şarkı geçişi ve eşzamanlı kuyruk işlemleri daha güvenilir hale getirildi.

## 0.6 sürüm notu

Rust’a kısmi geçiş yapıldı: bazı ayarlar ve kullanıcı verileri Rust çekirdeğine taşındı. Geçiş devam ediyor.

## Özellikler

- Deezer hesabıyla giriş, beğenilen şarkılar ve çalma listeleri.
- Şarkıları listelere ekleme ve listelerden çıkarma, sıralama ve kitaplıkta arama.
- Deezer Flow, şarkı miksleri ve ana sayfaya sabitlenebilir hızlı erişim kartları.
- Çalma sırası, karıştırma, tekrar ve uyku zamanlayıcısı.
- Şarkıları veya çalma listelerini MP3 ve FLAC olarak indirme; indirme sırasını görüntüleme ve iptal etme.
- Wi‑Fi ve mobil veri için ayrı dinleme kalitesi; isteğe bağlı mobil veriyle indirme.
- Tema, albüm kapağı, oynatıcı ve uygulama simgesi için kişiselleştirme seçenekleri.
- Yatay ekran ve desteklenen cihazlarda yüksek yenileme hızı.

Deezer’daki şarkıların ve ses kalitelerinin kullanılabilirliği hesabına, bölgeye ve parçaya bağlıdır. İndirilen dosyalar **Müzik/PlasmaWave** klasörüne kaydedilir.

## Kaynak koddan derleme

Gerekenler: JDK 17, Android SDK ve NDK, Rust ve `cargo-ndk`.

```bash
git clone https://github.com/Asedwa/projekt_b-d.git
cd projekt_b-d/rust
cargo ndk -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs build -p android-ffi --release --locked
cd ../android
./gradlew assembleDebug
```

APK, `android/app/build/outputs/apk/debug/app-debug.apk` konumunda oluşturulur. Arayüz Kotlin ve Jetpack Compose, oynatma Media3, uygulama çekirdeği Rust ve UniFFI kullanır.

## Lisans

GPL-3.0-or-later.
