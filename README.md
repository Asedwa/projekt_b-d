<!--markdownlint-disable MD013 MD033 MD041 -->
<div align="center">
  <img src="docs/images/logo.png" alt="projekt_b-d Logo" height="160" />
  <h1>projekt_b-d</h1>

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

Selamlar herkese bir süredir kendi ihtiyacımız ve merakımız üzerine uğraştığımız açık kaynaklı bir Android müzik çalar projem vardı, artık paylaşılacak kıvama geldiği için sizinle de paylaşmak istedim. Adı projekt better dezeer.

Kısaca bahsetmek gerekirse telefondaki yerel müzik dosyalarıyla Deezer kütüphaneniz tek bir uygulamada, göz yormayan ve kafanıza göre özelleştirebileceğiniz bir arayüzde toplamak istiyordum. Arayüz tarafını Kotlin ve Jetpack Compose ile, arka plandaki çekirdek işleri ise performanslı olsun diye Rust ile yazdık.

İçinde neler var derseniz:

- **Deezer + Yerel Müzik bir arada:** Hem Deezer hesabınızdaki listeleri, Flow'u ve miksleri hem de cihazdaki ses dosyalarını tek yerden çalabiliyorsunuz.
- **FLAC / MP3 İndirme:** Parçaları veya listeleri çevrimdışı dinlemek için doğrudan MP3 ya da kayıpsız FLAC olarak telefona indirebiliyor.
- **Jam (Birlikte Dinleme):** Oda kodu oluşturup arkadaşlarınızla aynı anda, senkronize şekilde müzik dinleyebiliyorsunuz.
- **Fazlasıyla Kişiselleştirilebilir Arayüz:** Ana sayfadaki kartları ve widget'ları sürükle-bırak ile istediğiniz gibi dizebilirsiniz. AMOLED siyah mod, kendi temanızı yapabileceğiniz tema düzenleyici, albüm kapağına göre renk değiştiren saydam cam oynatıcı ve 12 farklı canlı arka plan var.
- **Ekstra araçlar:** Dinleme istatistikleri, uyku zamanlayıcısı, gizli mod gibi günlük kullanımda işe yarayan detayları da ekledim. Bütün verileriniz ve istatistikleriniz tamamen cihazda yerel olarak tutuluyor.

Kurmak isteyenler [GitHub Releases](https://github.com/Asedwa/projekt_b-d/releases/latest) kısmından doğrudan APK olarak indirip kurabilir. Sonraki güncellemeler için tekrar GitHub'a girmenize gerek yok, uygulamanın ayarlarından tek tuşla yeni sürümü kontrol edip güncelleyebiliyor.

GitHub & APK İndirme Linki: https://github.com/Asedwa/projekt_b-d

Deneyip "şurası şöyle olsa daha iyi olur" dediğiniz yerler veya denk geldiğiniz bir hata olursa yorumlarda yazarsanız çok sevinirim. Destek olmak isterseniz GitHub'dan bir yıldız bırakmanız da bizi bayağı motive eder. Şimdiden deneyen herkese teşekkürler!

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
