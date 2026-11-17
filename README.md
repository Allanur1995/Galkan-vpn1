# Galkan VPN (Android)

**Galkan VPN** — Türkmenistan we beýleki çäklendirilen torlar üçin Anti-DPI tehnologiýaly, AdMob reklamaly we Google Play Pro abunaly Android VPN programmasy.

---

## Aýratynlyklary / Features

1. **Anti-DPI & Stealth Gorag (Türkmenistan üçin sazlanan)**
   - Protokollar: `VLESS + Reality`, `Trojan-GFW`, `VMess + WS CDN`, `Shadowsocks AEAD`, `AmneziaWG`.
   - **TLS ClientHello Fragmentasiýa** we **SNI Maskalama** (`www.microsoft.com`, `cdn.cloudflare.net`, `www.apple.com` we başgalar).
   - **Şifrlenen DNS (DoH)**: Cloudflare `1.1.1.1`, Google `8.8.8.8`, Quad9 `9.9.9.9`, AdGuard DNS.
   - **Split Tunneling**: Ýerli bank we gündelik programmalary VPN-siz göni işledip bolýar.
   - **Öz açaryňy goşmak**: `vless://`, `vmess://`, `trojan://` we `ss://` açarlaryny goşup we TCP ping barlap bolýar.

2. **AdMob Reklama (Mugt wagt gazanmak)**
   - **Rewarded Video Ad**: Reklama görüp `+2 Sagat Mugt VPN` ýa-da `30m Pro Synag` açylýar.
   - **Banner & Interstitial Ad**: Diňe mugt ulanyjylar üçin görkezilýär (Pro alynsa awtomat aýrylýar).

3. **Premium / Pro (Google Play Billing)**
   - `galkan_pro_monthly` (Aýlyk Pro)
   - `galkan_pro_yearly` (Ýyllyk Pro)
   - `galkan_pro_lifetime` (Hemişelik VIP)

---

## GitHub-da APK nädip ýygnamaly? / How to Build APK on GitHub

Bu taslamada **`.github/workflows/build-apk.yml`** faýly taýýar goşulandyr:

1. Kody GitHub repositoryňize ýükläň (`git push` to `main` ýa-da `master`).
2. GitHub-da **Actions** bölümine giriň — **Build Galkan VPN APK** işiniň tamamlanmagyna garaşyň.
3. **Artifacts** aşagyndan **`Galkan-VPN-Debug-APK`** (`Galkan-VPN-debug.apk`) faýlyny göçürip alyň.
4. Isleg boýunça GitHub **Settings → Secrets and variables → Actions** bölüminde aşakdaky gizlin açarlary goşsaňyz, gol çekilen **Release APK / AAB** hem dörediler:
   - `KEYSTORE_BASE64` (base64 encoded `.jks` keystore)
   - `STORE_PASSWORD`
   - `KEY_PASSWORD`
   - `ADMOB_APP_ID`, `ADMOB_BANNER_ID`, `ADMOB_REWARDED_ID`, `ADMOB_INTERSTITIAL_ID` (häzirki wagtda Google-yň resmi Test AdMob ID-leri işjeň).
