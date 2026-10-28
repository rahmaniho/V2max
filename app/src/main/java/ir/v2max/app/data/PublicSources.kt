package ir.v2max.app.data

/**
 * منابع عمومی و رایگان کانفیگ که به‌صورت پیش‌فرض در برنامه ثبت می‌شوند.
 * این لیست به‌روزرسانی مداوم دارد؛ برنامه در هر بار باز شدن، آن‌ها را تازه می‌کند.
 */
data class PublicSource(
    val name: String,
    val url: String,
    val maxConfigs: Int = 250
)

object PublicSources {

    private val byUrl: Map<String, PublicSource> =
        ALL.associateBy { it.url.trim().trimEnd('/') }

    fun forUrl(url: String): PublicSource? = byUrl[url.trim().trimEnd('/')]

    val ALL: List<PublicSource> = listOf(
        PublicSource(
            name = "🇮🇷 الاراپلاس",
            url = "https://sb.elaraplus.ir/s/djMsMTk3OSwxNzgyNTU1Nzg111b8170c24",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🇮🇷 ایران‌یاب (youfoundamin)",
            url = "https://raw.githubusercontent.com/youfoundamin/V2rayCollector/main/mixed_iran.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🇮🇷 گودبای (کم‌پینگ ایران)",
            url = "https://raw.githubusercontent.com/alireza786786/Goodbaye_Collector/main/iran_ping_top10.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🇮🇷 خدای‌جو (IR-RU)",
            url = "https://raw.githubusercontent.com/KhodeJav/ir-ruse-sub/main/output/subscription.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🇮🇷 ماهان (Freedom-V2Ray)",
            url = "https://raw.githubusercontent.com/MahanKenway/Freedom-V2Ray/main/configs/mix_sub.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🇮🇷 توماس (ساب‌لیست)",
            url = "https://raw.githubusercontent.com/ThomasJasperthecat/sub/main/sublist1.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "✅ تأییدشده (Top 100)",
            url = "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/top100.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "⚡ آلیریوا (Top 100)",
            url = "https://raw.githubusercontent.com/Alirewa/V2ray-Configs/main/sub1.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🏆 متین (بهترین‌ها)",
            url = "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/super-sub.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🚀 باری‌فار",
            url = "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Sub1.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🌍 مورفیوس (Lite)",
            url = "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/lite.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🌍 چای‌سپیش",
            url = "https://raw.githubusercontent.com/chaishpish/free_server_v2ray/main/@TFMTProto",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🌍 NoMoreWalls",
            url = "https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list.txt",
            maxConfigs = 150
        )
    )
}
