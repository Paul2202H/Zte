package com.toolbox.ztefamily.data

/**
 * Reference list of ZTE / Nubia / RedMagic internal model codes, for display
 * only -- so a user who sees "NX679J" in `getprop ro.product.model` on their
 * own phone can tell which public model name that maps to. This carries no
 * firmware, no unlock images, and no programmer binaries.
 */
data class ModelRef(val code: String, val marketName: String, val brand: String)

object DeviceModels {
    val reference = listOf(
        ModelRef("NX563J", "ZTE Axon 30 Ultra", "ZTE"),
        ModelRef("NX606J", "ZTE Axon 40 Ultra", "ZTE"),
        ModelRef("NX609J", "ZTE Axon 40 SE", "ZTE"),
        ModelRef("NX616J", "ZTE Axon 50 Ultra", "ZTE"),
        ModelRef("NX619J", "ZTE Axon 50", "ZTE"),
        ModelRef("NX629J", "ZTE Axon 60", "ZTE"),
        ModelRef("NX651J", "ZTE Axon 60 Ultra", "ZTE"),
        ModelRef("NX659J", "ZTE S40 Pro", "ZTE"),
        ModelRef("NX669J", "ZTE S40", "ZTE"),
        ModelRef("NX679J", "Nubia Z50", "Nubia"),
        ModelRef("NX679S", "Nubia Z50S", "Nubia"),
        ModelRef("NX701J", "Nubia Z60 Ultra", "Nubia"),
        ModelRef("NX709S", "Nubia Z60S Pro", "Nubia"),
        ModelRef("NX729J", "Nubia Z70 Ultra", "Nubia"),
        ModelRef("NX779J", "RedMagic 8 Pro", "RedMagic"),
        ModelRef("NX789J", "RedMagic 9 Pro", "RedMagic"),
        ModelRef("NX799J", "RedMagic 9 Pro+", "RedMagic"),
        ModelRef("NX809J", "RedMagic 10 Pro", "RedMagic"),
        ModelRef("P725A12", "ZTE Blade A series", "ZTE"),
        ModelRef("P768A02", "ZTE Blade V series", "ZTE"),
        ModelRef("P845A02", "ZTE Blade A series", "ZTE"),
        ModelRef("P855A01", "ZTE Blade V40", "ZTE"),
        ModelRef("P855A23", "ZTE Blade V50", "ZTE"),
        ModelRef("P865A02", "ZTE Blade A series", "ZTE"),
        ModelRef("P870A01", "ZTE Blade V series", "ZTE"),
        ModelRef("P875A02", "ZTE Blade A series", "ZTE"),
        ModelRef("PQ83A01", "ZTE Blade series", "ZTE"),
        ModelRef("TP1803", "ZTE tablet", "ZTE")
    )

    fun lookup(code: String): ModelRef? =
        reference.firstOrNull { it.code.equals(code, ignoreCase = true) }
}
