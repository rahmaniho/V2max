package ir.v2max.app.data

/**
 * منابع عمومی و رایگان کانفیگ که به‌صورت پیش‌فرض در برنامه ثبت می‌شوند.
 * این لیست به‌روزرسانی مداوم دارد؛ برنامه در هر بار باز شدن، آن‌ها را تازه می‌کند.
 */
data class PublicSource(
    val name: String,
    val url: String,
    val maxConfigs: Int = 250,
    /**
     * پروتکل‌هایی که این منبع مخصوص آن‌هاست.
     * مجموعه‌ی خالی یعنی منبع ترکیبی است و همه‌ی پروتکل‌ها را در بر می‌گیرد.
     */
    val protocols: Set<Proto> = emptySet()
)

object PublicSources {

    private val byUrl: Map<String, PublicSource> by lazy {
        ALL.associateBy { it.url.trim().trimEnd('/') }
    }

    fun forUrl(url: String): PublicSource? = byUrl[url.trim().trimEnd('/')]

    /** منابعی که مخصوص یک پروتکل هستند (برای پروتکل‌هایی که هسته‌ی Xray پشتیبانی نمی‌کند) */
    fun forProto(proto: Proto): List<PublicSource> = ALL.filter { proto in it.protocols }

    /**
     * منابع اختصاصی پروتکل‌های هسته‌ی sing-box.
     * این فایل‌ها به‌صورت خودکار از چند کانال عمومی جمع‌آوری و به‌روزرسانی می‌شوند.
     */
    val SING_BOX_SOURCES: List<PublicSource> by lazy {
        ALL.filter { it.protocols.isNotEmpty() }
    }

    val ALL: List<PublicSource> = listOf(
        // ------------------------------------------------- sing-box protocols
        PublicSource(
            name = "⚡ Hysteria2 (به‌روز روزانه)",
            url = "https://raw.githubusercontent.com/10ium/VpnClashFaCollector/main/sub/all/hysteria2.txt",
            maxConfigs = 120,
            protocols = setOf(Proto.HYSTERIA2)
        ),
        PublicSource(
            name = "⚡ TUIC v5",
            url = "https://raw.githubusercontent.com/10ium/VpnClashFaCollector/main/sub/all/tuic.txt",
            maxConfigs = 60,
            protocols = setOf(Proto.TUIC)
        ),
        PublicSource(
            name = "⚡ AnyTLS",
            url = "https://raw.githubusercontent.com/10ium/VpnClashFaCollector/main/sub/all/anytls.txt",
            maxConfigs = 60,
            protocols = setOf(Proto.ANYTLS)
        ),
        PublicSource(
            name = "⚡ AnyTLS (ShatakVPN)",
            url = "https://raw.githubusercontent.com/ShatakVPN/ConfigForge-V2Ray/main/configs/anytls.txt",
            maxConfigs = 60,
            protocols = setOf(Proto.ANYTLS)
        ),
        PublicSource(
            name = "⚡ WireGuard (کلودفلر/وپس)",
            url = "https://raw.githubusercontent.com/10ium/VpnClashFaCollector/main/sub/all/wireguard.txt",
            maxConfigs = 60,
            protocols = setOf(Proto.WIREGUARD)
        ),
        PublicSource(
            name = "🌍 ShatakVPN (همه پروتکل‌ها)",
            url = "https://raw.githubusercontent.com/ShatakVPN/ConfigForge-V2Ray/main/configs/all.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🌍 VpnClashFa (ترکیبی)",
            url = "https://raw.githubusercontent.com/10ium/VpnClashFaCollector/main/sub/all/mixed.txt",
            maxConfigs = 200
        ),
        PublicSource(
            name = "🌍 Au1rxx (فقط تأییدشده‌ها)",
            url = "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/v2ray-base64.txt",
            maxConfigs = 150,
            protocols = setOf(Proto.HYSTERIA2)
        ),
        PublicSource(
            name = "🌍 SylphNet",
            url = "https://raw.githubusercontent.com/ProblemTheCode/SylphNet-public/main/sub/sub_plain.txt",
            maxConfigs = 150,
            protocols = setOf(Proto.HYSTERIA2)
        ),
        PublicSource(
            name = "🌍 LeilaoMi (ادغام خودکار)",
            url = "https://raw.githubusercontent.com/LeilaoMi/AutoMergePublicNodes-Optimized/main/output/global.txt",
            maxConfigs = 150,
            protocols = setOf(Proto.HYSTERIA2)
        ),
        PublicSource(
            name = "🌍 Pusheen-V2Ray",
            url = "https://raw.githubusercontent.com/MahanKenway/Pusheen-V2Ray/main/configs/mix_sub.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🌍 kort0881 (VLESS)",
            url = "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/output/vless.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🏅 MohammadBahemmat (تست‌شده)",
            url = "https://raw.githubusercontent.com/MohammadBahemmat/V2ray-Collector/main/Tested.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🥇 SoliSpirit (SolVPN)",
            url = "https://raw.githubusercontent.com/SoliSpirit/SolVPN/main/all_configs.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🧪 lm705 (فقط زنده‌ها)",
            url = "https://raw.githubusercontent.com/lm705/vair/main/all_alive.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "📡 milad (V2RayCFGDumper)",
            url = "https://raw.githubusercontent.com/miladtahanian/V2RayCFGDumper/main/sub.txt",
            maxConfigs = 120
        ),
        PublicSource(
            name = "🇮🇷 مرتضی (ایرانسل)",
            url = "https://raw.githubusercontent.com/morteza-v2/free-v2ray-irancell-config/main/Sub1.txt",
            maxConfigs = 120
        ),
        PublicSource(
            name = "🇮🇷 ALIILAPRO (v2rayNG-Config)",
            url = "https://raw.githubusercontent.com/ALIILAPRO/v2rayNG-Config/main/server.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🌐 barry-far (Sub2)",
            url = "https://raw.githubusercontent.com/barry-far/v2ray-config/main/Sub2.txt",
            maxConfigs = 150
        ),
        PublicSource(
            name = "🌐 hans-thomas",
            url = "https://raw.githubusercontent.com/hans-thomas/v2ray-subscription/master/servers.txt",
            maxConfigs = 100
        ),
        PublicSource(
            name = "🌐 pornnewbee (VLESS)",
            url = "https://raw.githubusercontent.com/pornnewbee/free-vless-VPN/main/vless.txt",
            maxConfigs = 100
        ),
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
            maxConfigs = 150,
            protocols = setOf(Proto.HYSTERIA2)
        )
    )
}
