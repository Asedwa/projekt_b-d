# projekt_b-d 🎧

[![Son Sürüm](https://img.shields.io/github/v/release/Asedwa/projekt_b-d?label=S%C3%BCr%C3%BCm&style=flat-square&color=3daee9)](https://github.com/Asedwa/projekt_b-d/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/Asedwa/projekt_b-d/releases/latest)
[![Kotlin + Compose](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#mimari)
[![Rust + UniFFI](https://img.shields.io/badge/Rust-UniFFI%20Core-DEA584?style=flat-square&logo=rust&logoColor=black)](#mimari)
[![Lisans](https://img.shields.io/badge/Lisans-GPL--3.0--or--later-blue?style=flat-square)](#lisans)

**projekt_b-d**, Deezer kitaplığını, çalma listelerini ve cihazındaki yerel müzikleri yüksek performanslı, kişiselleştirilebilir bir **Kotlin + Jetpack Compose + Rust** oynatıcısında bir araya getiren modern bir Android müzik uygulamasıdır.

---

## 📥 İndir ve Kur

👉 [**Güncel Android APK'sını İndir (Releases)**](https://github.com/Asedwa/projekt_b-d/releases/latest)

1. Açılan sürüm sayfasında **Assets** bölümünden `projekt_b-d-<sürüm>.apk` dosyasını indir.
2. APK'yı açıp yükle (R8 optimizasyonlu release derlemesidir).
3. İlk kurulumdan sonra uygulama, yeni sürümleri **uygulama içinden otomatik olarak** denetler, indirir ve tek dokunuşla kurmanı sağlar (**Ayarlar → Eklentiler ve Hakkında → Güncellemeler**).

---

## ✨ Öne Çıkan Özellikler

### 🎨 Tasarım, Tema ve Tam Ekran Oynatıcı
- **Saydam Cam Now Playing:** Çalan albüm kapağından Rust palet motoruyla üretilen derin ışık hüzmeleri, saydam cam yüzeyler ve kesintisiz geçiş animasyonu.
- **12 Canlı Ortam Arka Planı:** İpek Aurora, Nebula Küreleri, Okyanus Dalgaları, Siber Halkalar, Yıldız Tozu, Prizma Hüzmeleri, Kuzey Perdesi, Güneş Patlaması, Dijital Yağmur, Kadife Dalgalar, Kozmik Girdap ve Ateş Böcekleri — tam ekran canlı önizleme ve basılı tutarak deseni inceleme desteğiyle.
- **Kişiselleştirme:** Hazır ve özel temalar, dinamik albüm renkleri, özelleştirilebilir oynatıcı alt cep tuşları, ilerleme çubuğu stilleri, uygulama ikonu ve özel başlık logosu.
- **120 Hz Akıcı Arayüz:** Sıfır tahsisli (zero-alloc) shader önbelleği ve kaydırma sırasında arka plan optimizasyonu ile akıcı liste deneyimi.

### 🎵 Müzik, Kitaplık ve Çevrimdışı Dinleme
- **Deezer Entegrasyonu:** Hesap girişi, beğenilen şarkılar, çalma listeleri, Deezer Flow ve şarkı miksleri.
- **Çevrimdışı İndirme:** Şarkı veya çalma listelerini MP3 ve FLAC olarak cihazına indir (`Müzik/PlasmaWave`), indirme sırasını yönet; Wi-Fi ve mobil veri için ayrı kalite seçimi yap.
- **Yerel Müzik Oynatıcı:** Rust tabanlı hızlı yerel ses tarayıcısı, gömülü kapak okuyucu ve 10 sn altı kısa sesleri filtreleme.

### 👥 Jam (Birlikte Dinle)
- **Gerçek Zamanlı Eşzamanlı Dinleme:** TLS şifreli MQTT üzerinden oda koduyla arkadaşlarınla aynı şarkıyı saniyesi saniyesine birlikte dinle; isteğe bağlı misafir oynatma/ilerletme kontrolü.

### 🧩 Modüler Ana Sayfa ve İstatistikler
- **Özelleştirilebilir Ana Sayfa:** Ana sayfaya basılı tutarak widget'ları sürükle-bırak ile sırala, boyut/stil değiştir veya yeni widget ekle.
- **Dinleme İstatistikleri ve Devamlılık:** Toplam dinleme süresi, en çok çalınan parçalar/sanatçılar, kaldığın yerden devam et, uyku zamanlayıcısı ve gizli dinleme oturumu.

### 🔄 Otomatik Güncelleme
- Uygulama açılışında GitHub Releases üzerinden yeni sürümü otomatik denetler, sürüm notlarını gösterir, APK'yı uygulama içinde indirip doğrulayarak kurulumu başlatır.

---

## 📋 Sürüm Notları

### `0.12.0` — İlk Açık Sürüm & Otomatik Güncelleme
- **Uygulama içi otomatik güncelleme:** GitHub Releases üzerinden otomatik sürüm kontrolü, ilerleme çubuklu güvenli APK indirme, tek dokunuşla kurulum ve Ayarlar üzerinden manuel denetim.
- **Açık kaynak çıkışı:** Yenilenen GitHub sayfası, sürüm akışı ve dokümantasyon.

### `0.11.x` — Cam Now Playing, Güvenlik, Jam & Akıcılık
- **0.11.2:** Ana sayfa kaydırma akıcılığı optimize edildi (ortam arka planı 30 FPS + sıfır tahsisli shader önbelleği + kaydırmada dondurma ve hedef boyutta kapak çözme).
- **0.11.1:** Jam misafir ilerletme, şarkı atlama ve ara belleğe alma senkronizasyonu düzeltildi.
- **0.11.0:** Rust palet motoru destekli saydam cam Now Playing ekranı, tam ekran ortam önizlemesinde basılı tutarak deseni görme ve kapsamlı güvenlik sertleştirmesi (MQTT TLS 8883, Jam davet onayı, WebView/yönlendirme/indirme yolu ve yedekleme güvenliği).

<details>
<summary>Daha önceki sürümler (0.6 – 0.10.5)</summary>

- **0.10.x:** 6 yeni hareketli ortam arka planı ve tam ekran canlı önizleme, Rust tabanlı yerel müzik tarayıcı, 10 sn kısa ses filtresi, rastgele tema, oynatıcı alt cep tuşları ve yeni uygulama logosu.
- **0.9.x:** R8 release derlemesi, LazyColumn sanallaştırma, modüler ana sayfa widget düzenleme, kesintisiz Media3 kuyruk geçişleri ve yedek albüm kapağı çözümü.
- **0.8:** Breeze tarzı sade koyu arayüz ve dinamik albüm renkleri.
- **0.6 – 0.7:** Rust + UniFFI çekirdeğine geçiş ve kilit ekranı / arka plan oynatma kararlılığı.

</details>

---

## 🏗️ Mimari

- **Arayüz:** Kotlin & Jetpack Compose (Material 3)
- **Oynatma:** AndroidX Media3 / ExoPlayer
- **Çekirdek:** Rust (`plasmawave-core`, SQLite depolama, palet & öneri motoru) + Mozilla UniFFI
- **Eşzamanlı Dinleme:** TLS üzerinden MQTT (`MqttRelayClient` & `JamManager`)

Daha fazla teknik ayrıntı için [ARCHITECTURE.md](ARCHITECTURE.md) dosyasına göz atabilirsin.

---

## 🛠️ Kaynak Koddan Derleme

**Gereksinimler:** JDK 17, Android SDK & NDK, Rust ve [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk).

```bash
git clone https://github.com/Asedwa/projekt_b-d.git
cd projekt_b-d/rust
cargo ndk -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs build -p android-ffi --release --locked
cd ../android
./gradlew :app:assembleRelease
```

Derlenen APK `android/app/build/outputs/apk/release/app-release.apk` konumunda oluşturulur.

> Deezer'daki şarkıların ve ses kalitelerinin kullanılabilirliği hesabına, bölgeye ve parçaya bağlıdır. İndirilen dosyalar cihazda **Müzik/PlasmaWave** klasörüne kaydedilir.

---

## 📄 Lisans

Bu proje **GPL-3.0-or-later** lisansı ile lisanslanmıştır.
