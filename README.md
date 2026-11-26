<!--markdownlint-disable MD013 MD033 MD041 -->
<div align="center">
  <img src="docs/images/logo.png" alt="projekt_b-d Logo" height="160" />
  <h1>projekt_b-d</h1>
  <p>
    projekt_b-d, Deezer kitaplığını ve cihazındaki yerel müzikleri Kotlin, Jetpack Compose
    ve Rust çekirdeğiyle bir araya getiren modern ve kişiselleştirilebilir bir Android müzik oynatıcısıdır.
  </p>

  <p>
    <a href="https://github.com/Asedwa/projekt_b-d/releases/latest">
      <img src="https://img.shields.io/github/v/release/Asedwa/projekt_b-d?label=S%C3%BCr%C3%BCm&style=flat&color=3daee9" alt="Son Sürüm" />
    </a>
    <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat&logo=android&logoColor=white" alt="Android 8.0+" />
    <img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat&logo=kotlin&logoColor=white" alt="Kotlin Compose" />
    <img src="https://img.shields.io/badge/Rust-000000?style=flat&logo=rust&logoColor=white" alt="Rust" />
    <a href="#lisans">
      <img src="https://img.shields.io/badge/Lisans-GPL--3.0-blue?style=flat" alt="Lisans" />
    </a>
  </p>

  <br />

  <p>
    <a href="https://github.com/Asedwa/projekt_b-d/releases/latest">
      <img src="https://img.shields.io/badge/APK%20%C4%B0ndir-GitHub%20Releases-3daee9?style=for-the-badge&logo=android&logoColor=white" alt="APK İndir" />
    </a>
  </p>
</div>

## Genel Bakış

projekt_b-d; Deezer hesabını, çalma listelerini ve cihazındaki yerel ses dosyalarını tek bir sade arayüzde toplar. Parçaları çevrimdışı dinlemek için indirebilir, ana sayfayı kendi widget düzenine göre şekillendirebilir ve arkadaşlarınla aynı anda müzik dinleyebilirsin.

Kitaplık, çalma listeleri, temalar ve dinleme istatistikleri cihazda yerel olarak saklanır; yeni sürümler uygulama içinden otomatik olarak denetlenir.

## Özellikler

- **Tema ve Görünüm**: Hazır temalar, renk uyumu destekli özel tema düzenleyici, dinamik albüm renkleri ve AMOLED siyah modu.
- **Now Playing**: Albüm kapağından üretilen saydam cam oynatıcı ve 12 canlı ortam arka planı.
- **Deezer ve Yerel Müzik**: Deezer kitaplığı, Flow, miksler ve cihazdaki yerel müzik dosyalarını tek yerden oynatma.
- **Çevrimdışı İndirme**: Şarkı ve çalma listelerini MP3 veya FLAC olarak `Müzik/PlasmaWave` klasörüne kaydetme.
- **Jam (Birlikte Dinle)**: Oda koduyla arkadaşlarınla gerçek zamanlı eşzamanlı müzik dinleme.
- **Modüler Ana Sayfa**: Sürükle-bırak widget düzenleme, kaldığın yerden devam et ve özelleştirilebilir hızlı kartlar.
- **İstatistikler ve Araçlar**: Dinleme süresi takibi, uyku zamanlayıcısı ve gizli dinleme modu.
- **Otomatik Güncelleme**: GitHub Releases üzerinden yeni sürümleri uygulama içinde denetleyip tek dokunuşla kurma.

<!--
Ekran görüntülerini `docs/images/` klasörüne ekleyip aşağıdaki bölümü açabilirsiniz:

## Ekran Görüntüleri

<div align="center">
  <img src="docs/images/home.png" width="240" alt="Ana Sayfa" />
  <img src="docs/images/player.png" width="240" alt="Oynatıcı" />
  <img src="docs/images/themes.png" width="240" alt="Temalar" />
</div>
-->

## Kurulum

1. [Releases](https://github.com/Asedwa/projekt_b-d/releases/latest) sayfasından `projekt_b-d-<sürüm>.apk` dosyasını indir.
2. APK'yı cihazına kur.
3. Sonraki güncellemeleri **Ayarlar → Eklentiler ve Hakkında → Güncellemeler** üzerinden doğrudan indirip kurabilirsin.

## Kaynak Koddan Derleme

Gereksinimler: JDK 17, Android SDK & NDK, Rust ve [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk).

```bash
git clone https://github.com/Asedwa/projekt_b-d.git
cd projekt_b-d/rust
cargo ndk -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs build -p android-ffi --release --locked
cd ../android
./gradlew :app:assembleRelease
```

APK çıktısı `android/app/build/outputs/apk/release/app-release.apk` konumunda oluşturulur.

## Lisans

Bu proje **GPL-3.0-or-later** lisansı ile lisanslanmıştır.
