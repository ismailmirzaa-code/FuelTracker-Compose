# 🚗 Akıllı Araç & Yakıt Takip Asistanı

Jetpack Compose ve modern Android mimarisiyle geliştirilmiş; yakıt tüketimi, periyodik bakım masrafları ve seyahat bütçesi takibi sağlayan akıllı araç asistanı.

## ✨ Öne Çıkan Özellikler

* ⛽ **Yakıt Fişi Takibi:** Depo dolum kayıtları, kilometre ve tutar geçmişi.
* 📸 **Akıllı Fiş Tarama (OCR):** Google ML Kit ile pompa fişlerinin fotoğrafından Litre ve Tutar bilgisini otomatik tanıma.
* 🚗 **Tüketim Analizi & Trip KM:** İki dolum arası yapılan yol ve 100 km'deki anlık tüketim ortalamaları.
* 🔧 **Periyodik Bakım & Muayene:** Yağ değişimi km sayacı, muayene bitiş tarihi ve servis kayıtları.
* 🛣️ **Yolculuk Maliyet Hesaplayıcı:** Gidilecek mesafeye göre gereken yakıtı ve masrafı anında hesaplama.
* 📄 **PDF Servis Raporu:** Araç satışı veya ekspertiz için tüm bakım geçmişini A4 formatında PDF olarak dışa aktarma.
* 💰 **Aylık Bütçe Raporu:** Ay ay yakıt ve bakım giderlerinin dökümü.
* 💾 **Güvenli Veri:** Room Database ve DataStore Preferences altyapısı + CSV formatında dışa aktarım.

## 🛠️ Kullanılan Teknolojiler & Kütüphaneler

* **UI:** Jetpack Compose & Material 3
* **Veritabanı:** Room Database (Flow & Coroutines)
* **Yapay Zeka / Görüntü İşleme:** Google ML Kit Text Recognition
* **Veri Depolama:** Jetpack DataStore Preferences
* **Raporlama:** Android Graphics & PdfDocument API
* **Mimari:** MVVM & Clean Architecture

## 📲 Kurulum

1. Depoyu klonlayın:
   ```bash
   git clone https://github.com/KULLANICI_ADINIZ/REPO_ADINIZ.git
