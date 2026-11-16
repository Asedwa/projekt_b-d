# Repository workflow

- When the user requests a GitHub update, publish an installable Android APK for the same source commit as part of that update. Include the Android version in its filename, such as `projekt_b-d-0.4.apk`. Use `scripts/publish-apk.sh` and keep the download link in the README working.
- Ask the user before running unit tests (`testDebugUnitTest` etc.). A debug build (`assembleDebug`) to check that the project compiles does NOT need approval.
- Do not publish changes (push, APK release) unless the user requested or approved it. Local git commits are NOT backups or publishing; they are governed by the rules below.

## Git kuralları

- Git deposu proje klasörünün kendisinde değil, `plasmawave/` alt klasöründedir. Tüm git komutlarını `git -C plasmawave ...` ile ya da `plasmawave/` içinde çalıştır.
- Her göreve başlamadan önce `git status` temiz mi bak. Temiz değilse dur ve bana söyle.
- Kodu değiştiren görev bitince `assembleDebug` ile derle. Derleme hata verirse COMMIT ATMA, durup hatayı bana anlat. Soru ya da açıklama görevlerinde commit atma.
- Derleme geçerse `git diff --stat` ile değişiklikleri kontrol et. Görevle ilgisiz bir dosya değişmişse commit atmadan önce bana söyle.
- Sonra `git add -A` ve kısa, Türkçe, ne yaptığını anlatan bir mesajla `git commit` et.
- Ben açıkça "GitHub'u
