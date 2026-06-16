package com.yusufulgen.cuzdan.data.model

data class ReleaseNote(
    val version: String,
    val features: List<String>
)

object ReleaseNotesProvider {
    val notes = listOf(
        ReleaseNote(
            version = "1.5",
            features = listOf(
                "Kategori bazlı bağımsız para birimi yönetimi (Para Birimi İzolasyonu) getirildi.",
                "Raporlar sayfasındaki fiyat ve portföy senkronizasyonu hataları giderildi.",
                "İşlemler (Ayarlar) sayfası ikonları dinamik vektör formatına geçirildi; hem açık hem koyu temaya tam uyumlu hale getirildi.",
                "Gereksiz kaynaklar temizlenerek uygulama boyutu yaklaşık 20 MB hafifletildi."
            )
        ),
        ReleaseNote(
            version = "1.4",
            features = listOf(
                "Dolar/TL dönüşümündeki hatalı maliyet hesaplama ortadan kalktı."
            )
        ),
        ReleaseNote(
            version = "1.3",
            features = listOf(
                "Eldeki maliyet gösterimi eklendi.\n",
                "Döviz ve altın verilerindeki sapmalar giderildi.\n",
                "Yerel saat dilimine göre gece yarısı veri sıfırlama mantığı iyileştirildi.\n",
                "Kullanıcı arayüzünde ufak iyileştirmeler yapıldı."
            )
        )
    )
}
