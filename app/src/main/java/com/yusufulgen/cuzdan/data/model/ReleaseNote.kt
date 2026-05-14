package com.yusufulgen.cuzdan.data.model

data class ReleaseNote(
    val version: String,
    val features: List<String>
)

object ReleaseNotesProvider {
    val notes = listOf(
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
