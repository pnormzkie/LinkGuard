package com.linkguard.app.scanner

import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import com.linkguard.app.util.DomainExtractor
import java.util.regex.Pattern

// ─── URL Extractor ────────────────────────────────────────────────────────────

object UrlExtractor {

    private val URL_PATTERN: Pattern = Pattern.compile(
        "(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+)",
        Pattern.CASE_INSENSITIVE
    )

    private val BARE_DOMAIN_PATTERN: Pattern = Pattern.compile(
        "(?<![\\w@])([\\w\\-]+\\.[a-z]{2,}(?:/[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]*)?)",
        Pattern.CASE_INSENSITIVE
    )

    private val WHITELISTED_DOMAINS = setOf(
        "gmail.com", "yahoo.com", "hotmail.com", "outlook.com",
        "facebook.com", "instagram.com", "twitter.com", "tiktok.com",
        "google.com", "apple.com", "microsoft.com", "amazon.com",
        "youtube.com", "linkedin.com", "github.com", "reddit.com",
        "netflix.com", "paypal.com",
        "bpi.com.ph", "bdo.com.ph", "metrobank.com.ph", "landbank.com",
        "gcash.com", "maya.ph", "shopee.ph", "lazada.com.ph"
    )

    fun extractUrls(text: String): List<String> {
        val urls = mutableListOf<String>()

        val matcher1 = URL_PATTERN.matcher(text)
        while (matcher1.find()) {
            urls.add(matcher1.group(1) ?: continue)
        }

        val matcher2 = BARE_DOMAIN_PATTERN.matcher(text)
        while (matcher2.find()) {
            val domain = matcher2.group(1) ?: continue
            val domainLower = domain.lowercase()

            if (urls.any { it.contains(domainLower) }) continue
            if (domain.length < 5) continue

            val hostPart = domainLower.substringBefore("/")

            val allowedTlds = listOf(
                ".com", ".net", ".org", ".io", ".co",
                ".ph", ".com.ph", ".net.ph", ".org.ph"
            )

            val looksLikeRealBareDomain = allowedTlds.any { tld ->
                hostPart.endsWith(tld)
            }

            if (!looksLikeRealBareDomain) continue

            if (WHITELISTED_DOMAINS.any { hostPart == it || hostPart.endsWith(".$it") }) continue

            urls.add("https://$domain")
        }

        return urls.distinct()
    }
}

// ─── Heuristic Scanner ────────────────────────────────────────────────────────

object HeuristicScanner {

    private val SUSPICIOUS_TLDS = listOf(
        ".tk", ".ml", ".ga", ".cf", ".gq",
        ".xyz", ".top", ".click", ".download", ".ru", ".cc", ".su",
        ".pw", ".ws", ".biz", ".info", ".link", ".online", ".site",
        ".shop", ".store", ".live", ".club", ".vip", ".win", ".loan",
        ".work", ".party", ".review", ".stream", ".gdn", ".racing",
        ".io", ".ph-", "ph.cc", ".buzz", ".icu", ".cyou"
    )

    private val URL_SHORTENERS = listOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly",
        "short.link", "rb.gy", "cutt.ly", "is.gd", "buff.ly",
        "tiny.cc", "shorte.st", "adf.ly", "bc.vc", "s.id",
        "v.gd", "clck.ru", "qr.ae", "to.ly", "x.co",
        "lnkd.in", "fb.me", "wa.me", "go2l.ink", "shorturl.at"
    )

    private val DANGEROUS_EXTENSIONS = listOf(
        ".exe", ".apk", ".bat", ".cmd", ".msi", ".ps1",
        ".vbs", ".jar", ".scr", ".pif", ".reg",
        ".zip", ".rar", ".7z", ".docm", ".xlsm", ".pptm"
    )

    private val PH_BRANDS = listOf(
        "bpi", "bdo", "metrobank", "landbank", "pnb", "rcbc",
        "unionbank", "security bank", "eastwest", "chinabank",
        "gcash", "maya", "paymaya", "grab", "shopee", "lazada",
        "sss", "pagibig", "philhealth", "dti", "bir"
    )

    private val GLOBAL_BRANDS = listOf(
        "paypal", "amazon", "apple", "microsoft", "google",
        "netflix", "facebook", "instagram", "twitter",
        "dhl", "fedex", "ups", "visa", "mastercard"
    )

    private val ALL_BRANDS = PH_BRANDS + GLOBAL_BRANDS

    private val OFFICIAL_DOMAINS = mapOf(
        "bpi"         to listOf("bpi.com.ph", "www.bpi.com.ph", "bpiexpressonline.com", "www.bpiexpressonline.com"),
        "bdo"         to listOf("bdo.com.ph", "www.bdo.com.ph"),
        "gcash"       to listOf("gcash.com", "www.gcash.com", "globe.com.ph"),
        "maya"        to listOf("maya.ph", "www.maya.ph", "paymaya.com", "www.paymaya.com"),
        "paymaya"     to listOf("paymaya.com", "www.paymaya.com", "maya.ph", "www.maya.ph"),
        "paypal"      to listOf("paypal.com", "www.paypal.com"),
        "google"      to listOf("google.com", "www.google.com", "google.com.ph", "googlesyndication.com", "googleadservices.com", "doubleclick.net"),
        "apple"       to listOf("apple.com", "www.apple.com"),
        "microsoft"   to listOf("microsoft.com", "www.microsoft.com"),
        "facebook"    to listOf("facebook.com", "www.facebook.com", "fb.com"),
        "amazon"      to listOf("amazon.com", "www.amazon.com"),
        "metrobank"   to listOf("metrobank.com.ph", "www.metrobank.com.ph"),
        "landbank"    to listOf("landbank.com", "www.landbank.com", "lbp-eservices.com"),
        "unionbank"   to listOf("unionbankph.com", "www.unionbankph.com"),
        "rcbc"        to listOf("rcbc.com", "www.rcbc.com"),
        "shopee"      to listOf("shopee.ph", "www.shopee.ph", "shopee.com"),
        "lazada"      to listOf("lazada.com.ph", "www.lazada.com.ph", "lazada.com"),
        "grab"        to listOf("grab.com", "www.grab.com"),
        "netflix"     to listOf("netflix.com", "www.netflix.com"),
        "instagram"   to listOf("instagram.com", "www.instagram.com"),
        "twitter"     to listOf("twitter.com", "www.twitter.com", "x.com"),
        "visa"        to listOf("visa.com", "www.visa.com"),
        "mastercard"  to listOf("mastercard.com", "www.mastercard.com"),
        "dhl"         to listOf("dhl.com", "www.dhl.com", "dhl.com.ph"),
        "fedex"       to listOf("fedex.com", "www.fedex.com"),
        "sss"         to listOf("sss.gov.ph", "www.sss.gov.ph"),
        "pagibig"     to listOf("pagibig.gov.ph", "www.pagibig.gov.ph", "hdmf.gov.ph"),
        "philhealth"  to listOf("philhealth.gov.ph", "www.philhealth.gov.ph"),
        "bir"         to listOf("bir.gov.ph", "www.bir.gov.ph")
    )

    private val PHISHING_KEYWORDS = listOf(
        "login", "verify", "account", "secure", "update", "confirm",
        "validate", "authenticate", "suspend", "locked", "unlock",
        "reward", "redeem", "cashback", "rebate", "refund", "prize",
        "winner", "won", "claim", "gift", "promo", "bonus", "points",
        "peso", "payout", "transfer", "expire", "expiring",
        "urgent", "immediately", "limited", "final", "last", "warning",
        "alert", "notice", "padala", "bayad", "pera", "tulong", "libre", "panalo",
        "streak", "odds", "luck", "paid", "registration", "incredible", "winc", "voucher"
    )

    private val SMISHING_PATTERNS = listOf(
        Regex("(\\d+[,.]?\\d*) points? (will )?expire", RegexOption.IGNORE_CASE),
        Regex("(php|₱)\\s?[\\d,]+.*cash.?back", RegexOption.IGNORE_CASE),
        Regex("(php|₱)\\s?[\\d,]+.*reward", RegexOption.IGNORE_CASE),
        Regex("congratulations.*pending", RegexOption.IGNORE_CASE),
        Regex("click.*link.*claim", RegexOption.IGNORE_CASE),
        Regex("visit.*redeem", RegexOption.IGNORE_CASE),
        Regex("account.*suspend", RegexOption.IGNORE_CASE),
        Regex("verify.*within.*hour", RegexOption.IGNORE_CASE),
        Regex("otp.*share", RegexOption.IGNORE_CASE),
        Regex("parcel.*held|package.*detained", RegexOption.IGNORE_CASE),
        Regex("delivery.*fee.*pay", RegexOption.IGNORE_CASE),
        Regex("(winning|incredible).*luck", RegexOption.IGNORE_CASE),
        Regex("ultra high odds", RegexOption.IGNORE_CASE),
        Regex("winning streak", RegexOption.IGNORE_CASE),
        Regex("just got paid", RegexOption.IGNORE_CASE),
        Regex("cash out.*winning", RegexOption.IGNORE_CASE),
        // Tagalog / Taglish smishing patterns (common PH scam texts)
        Regex("nanalo\\s+ka", RegexOption.IGNORE_CASE),
        Regex("i-?\\s?claim\\s+(mo|ang|na|ngayon)", RegexOption.IGNORE_CASE),
        Regex("libreng\\s+(load|premyo|regalo|gift|points?)", RegexOption.IGNORE_CASE),
        Regex("i-?\\s?click\\s+(ang|ito|dito|lang)", RegexOption.IGNORE_CASE),
        Regex("(na|naka)-?\\s?block.{0,30}account|account.{0,30}(na|naka)-?\\s?block", RegexOption.IGNORE_CASE),
        Regex("(panalo|premyo).{0,40}(claim|kunin|i-?redeem)", RegexOption.IGNORE_CASE),
        Regex("(pera|cash).{0,40}(padala|matatanggap|makukuha)", RegexOption.IGNORE_CASE),
        Regex("(ma-?expire|mag-?e-?expire).{0,30}(points?|load|account)", RegexOption.IGNORE_CASE),
        Regex("(i-?verify|verify).{0,20}(agad|ngayon|kaagad)", RegexOption.IGNORE_CASE),
        Regex("huwag.{0,30}ibahagi.{0,30}otp|otp.{0,30}huwag", RegexOption.IGNORE_CASE),
        // Courier / customs "fee to release" scams (English + Taglish)
        Regex("(release|processing|customs|clearance|shipping)\\s+fee", RegexOption.IGNORE_CASE),
        Regex("(parcel|package|padala).{0,30}(on hold|nakahold|naka-?hold|nakabinbin|detained)", RegexOption.IGNORE_CASE),
        Regex("(bayad|bayaran).{0,30}(padala|parcel|package|koreo|delivery)", RegexOption.IGNORE_CASE),
        // Job / work-from-home recruitment scams
        Regex("(work\\s?from\\s?home|trabaho sa bahay).{0,40}(payout|kita|sweldo|daily|araw-?araw)", RegexOption.IGNORE_CASE),
        Regex("(kumita|earn).{0,25}(daily|araw-?araw|agad|\\$\\d|php|₱)", RegexOption.IGNORE_CASE),
        Regex("(hiring|nag-?hi?ahanap|nangangailangan).{0,40}(no experience|walang experience|apply now|mag-?apply)", RegexOption.IGNORE_CASE),
        // Tech-support / account-compromise scare scams
        Regex("(account|device|phone|computer).{0,20}(hacked|compromised|na-?hack|infected)", RegexOption.IGNORE_CASE),
        Regex("virus.{0,20}detected|detected.{0,20}virus", RegexOption.IGNORE_CASE),
        Regex("(call|tumawag|tawag).{0,15}(now|agad|immediately).{0,25}(support|technician|microsoft|apple)", RegexOption.IGNORE_CASE)
    )

    private val HOMOGRAPH_MAP = mapOf(
        '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a',
        '5' to 's', '6' to 'g', '7' to 't', '8' to 'b'
    )

    private val PROTECTED_DOMAINS = listOf(
        "paymaya", "gcash", "bpi", "bdo", "metrobank",
        "landbank", "unionbank", "paypal", "amazon", "apple",
        "microsoft", "google", "facebook", "netflix"
    )

    /**
     * Well-known legitimate domains that are not brand-spoofing targets (so they are not in
     * OFFICIAL_DOMAINS) but are common enough that their normal auth/redirect flows must not
     * be heuristically flagged. Kept deliberately small and curated — every entry here fully
     * bypasses the heuristics for that registrable domain and its subdomains. Subdomain
     * coverage comes from the endsWith check in scanWithContext.
     */
    private val ADDITIONAL_TRUSTED_DOMAINS = setOf(
        "battle.net", "blizzard.com",
        "steampowered.com", "steamcommunity.com",
        "discord.com",
        "epicgames.com",
        "riotgames.com"
    )

    private val ALL_OFFICIAL_DOMAINS: Set<String> = buildSet {
        OFFICIAL_DOMAINS.values.forEach { addAll(it) }
        addAll(ADDITIONAL_TRUSTED_DOMAINS)
    }

    /**
     * True if any alphabetic label mixes Unicode scripts within a single label (e.g. Latin
     * letters alongside Cyrillic/Greek lookalikes) — the hallmark of a homoglyph attack like
     * "pаypal.com" (Cyrillic 'а'). COMMON/INHERITED code points (digits, hyphen) are ignored.
     * All-one-script IDNs (e.g. a fully Cyrillic domain) are NOT flagged here, to avoid
     * penalizing legitimate internationalized domains.
     */
    private fun hasMixedScript(domain: String): Boolean {
        domain.split(".", "/", "@").forEach { label ->
            val scripts = label
                .filter { Character.isLetter(it) }
                .map { Character.UnicodeScript.of(it.code) }
                .filterNot { it == Character.UnicodeScript.COMMON || it == Character.UnicodeScript.INHERITED }
                .toSet()
            if (scripts.size > 1) return true
        }
        return false
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }

    /**
     * Words attackers glue onto a brand name to look legitimate ("verifypaypal",
     * "paypalsupport", "secure-amazon"). Used to tell a real spoof from an ordinary word
     * that merely contains a brand substring ("startups", "pineapple", "appleton").
     */
    private val BRAND_AFFIXES = listOf(
        "secure", "security", "login", "signin", "logon", "account", "accounts",
        "verify", "verification", "verified", "update", "confirm", "support",
        "service", "services", "customer", "online", "official", "auth", "help",
        "billing", "payment", "payments", "wallet", "portal", "access", "recovery",
        "unlock", "alert"
    )

    /**
     * True if [host] looks like it is impersonating [brand] — i.e. the brand appears as a
     * full label, or glued to a known phishing affix. This catches concatenations like
     * "verifypaypal.com" / "paypalsupport.com" / "secure-paypal.com" while NOT matching a
     * brand that is merely a fragment of an ordinary word ("ups" in "startups", "apple" in
     * "pineapple"/"appleton"). Inputs are already lowercased. Reputation providers remain the
     * primary defense for novel/sophisticated lookalikes; this is the fast local pre-filter.
     */
    private fun looksLikeBrandSpoof(host: String, brand: String): Boolean {
        host.split('.', '-', '_').forEach { token ->
            if (token.isEmpty() || !token.contains(brand)) return@forEach
            if (token == brand) return true
            val remainder = token.replaceFirst(brand, "")
            if (BRAND_AFFIXES.any {
                    remainder == it || remainder.startsWith(it) || remainder.endsWith(it)
                }
            ) return true
        }
        return false
    }

    /**
     * True if [keyword] appears in [text] as a whole token (boundaries on both sides), so
     * "login" matches "/login" but not "/bloginfo", and "last" matches "/last" but not
     * "elastic". Inputs are already lowercased.
     */
    private fun containsKeyword(text: String, keyword: String): Boolean =
        Regex("(?<![a-z0-9])" + Regex.escape(keyword) + "(?![a-z0-9])").containsMatchIn(text)

    /**
     * True if [url] references a file with extension [ext] (which includes the leading dot)
     * at a real filename boundary — i.e. the extension is not immediately followed by another
     * alphanumeric char. Prevents ".bat" from matching inside "account.battle.net". Input is
     * already lowercased.
     */
    private fun urlReferencesFileType(url: String, ext: String): Boolean =
        Regex(Regex.escape(ext) + "(?![a-z0-9])").containsMatchIn(url)

    fun scan(url: String): ScanResult = scanWithContext(url, null)

    fun scanWithContext(url: String, messageText: String?): ScanResult {
        val flags = mutableListOf<String>()
        var score = 0

        val urlLower = url.lowercase()
        val domain = DomainExtractor.extract(urlLower) ?: urlLower
        val urlPath = extractPath(urlLower)
        // Official if the host matches a trusted domain exactly OR is a subdomain of one.
        // The leading dot in ".$it" prevents suffix spoofing (e.g. "secure-google.com" is
        // NOT a subdomain of "google.com").
        val isOfficialDomain = ALL_OFFICIAL_DOMAINS.any { domain == it || domain.endsWith(".$it") }

        // 1. HTTP
        if (!isOfficialDomain && urlLower.startsWith("http://")) {
            flags.add("Unencrypted HTTP connection")
            score += 30
        }

        // 2. Suspicious TLDs (match the host's actual suffix, not a substring anywhere in
        //    the URL — otherwise a TLD inside an embedded redirect param would misfire)
        if (!isOfficialDomain) {
            SUSPICIOUS_TLDS.forEach { tld ->
                if (domain.endsWith(tld)) {
                    flags.add("Suspicious domain extension ($tld)")
                    score += 25
                }
            }
        }

        // 3. URL Shorteners (always check — shorteners can wrap official domains)
        URL_SHORTENERS.forEach { shortener ->
            if (domain == shortener || domain.endsWith(".$shortener")) {
                flags.add("URL shortener detected — destination hidden")
                score += 30
            }
        }

        // 4. Phishing keywords (whole-token match so "login" doesn't fire on "/bloginfo"
        //    and "last" doesn't fire on "elastic")
        if (!isOfficialDomain) {
            PHISHING_KEYWORDS.forEach { kw ->
                when {
                    containsKeyword(domain, kw) -> {
                        flags.add("Phishing keyword in domain: \"$kw\"")
                        score += 15
                    }
                    containsKeyword(urlPath, kw) -> {
                        flags.add("Phishing keyword in URL path: \"$kw\"")
                        score += 10
                    }
                }
            }
        }

        // 5. Brand spoofing (brand as a full label or glued to a phishing affix, so
        //    "verifypaypal.com" is caught but "startups.com"/"pineapple.com" are not)
        if (!isOfficialDomain) {
            ALL_BRANDS.forEach { brand ->
                if (looksLikeBrandSpoof(domain, brand)) {
                    val officialList = OFFICIAL_DOMAINS[brand]
                        ?: listOf("$brand.com", "$brand.com.ph")
                    val isOfficial = officialList.any { domain == it || domain.endsWith(".$it") }
                    if (!isOfficial) {
                        flags.add("Possible brand spoofing: \"${brand.uppercase()}\"")
                        score += 40
                    }
                }
            }
        }

        // 6. Homograph / number-substitution
        if (!isOfficialDomain) {
            val normalizedDomain = domain.map { HOMOGRAPH_MAP[it] ?: it }.joinToString("")
            if (normalizedDomain != domain) {
                ALL_BRANDS.forEach { brand ->
                    if (normalizedDomain.contains(brand)) {
                        flags.add("Lookalike domain detected — uses numbers as letters")
                        score += 45
                    }
                }
            }
        }

        // 6b. IDN / homoglyph — punycode (xn--) or mixed-script labels impersonate real sites.
        //     ASCII digit-substitution is rule #6; this catches Unicode lookalikes that bypass it.
        if (!isOfficialDomain) {
            val labels = domain.split(".")
            if (labels.any { it.startsWith("xn--") }) {
                flags.add("Internationalized (punycode) domain — can disguise the real name")
                score += 25
                val decoded = runCatching { java.net.IDN.toUnicode(domain) }.getOrDefault(domain)
                if (ALL_BRANDS.any { decoded.contains(it, ignoreCase = true) }) {
                    flags.add("Lookalike domain — punycode mimics a known brand")
                    score += 45
                }
            }
            if (hasMixedScript(domain)) {
                flags.add("Domain mixes character sets — possible homoglyph spoofing")
                score += 45
            }
        }

        // 7. Excessive subdomains
        val parts = domain.split(".")
        if (!isOfficialDomain && parts.size > 4) {
            flags.add("Unusual number of subdomains")
            score += 15
        }

        // 8. Brand in subdomain
        if (!isOfficialDomain && parts.size >= 3) {
            val rootDomain = parts.takeLast(2).joinToString(".")
            val rootDomainPh = if (parts.size >= 3
                && parts[parts.size - 2] == "com"
                && parts.last() == "ph"
            ) parts.takeLast(3).joinToString(".") else null
            val subdomain = parts.dropLast(2).joinToString(".")

            ALL_BRANDS.forEach { brand ->
                if (looksLikeBrandSpoof(subdomain, brand)) {
                    val officialList = OFFICIAL_DOMAINS[brand]
                        ?: listOf("$brand.com", "$brand.com.ph")
                    val isOfficialRoot = officialList.any { official ->
                        rootDomain == official ||
                                rootDomain.endsWith(".$official") ||
                                rootDomainPh == official ||
                                (rootDomainPh != null && rootDomainPh.endsWith(".$official"))
                    }
                    if (!isOfficialRoot) {
                        flags.add("Brand name used in subdomain — classic phishing trick")
                        score += 50
                    }
                }
            }
        }

        // 9. IP address
        if (domain.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            flags.add("IP address used instead of domain name")
            score += 30
        }

        // 10. Long URL
        if (url.length > 200) {
            flags.add("Unusually long URL")
            score += 10
        }

        // 11. Excessive encoding
        if (!isOfficialDomain && url.count { it == '%' } > 5) {
            flags.add("Excessive URL encoding — possible obfuscation")
            score += 20
        }

        // 12. Scam TLDs
        if (!isOfficialDomain) {
            val scamTldPattern = Regex("\\.(cc|su|pw|buzz|icu|cyou|vip|win|loan)$")
            if (scamTldPattern.containsMatchIn(domain)) {
                flags.add("Domain uses TLD commonly associated with scams")
                score += 30
            }
        }

        // 13. Gibberish domain
        if (!isOfficialDomain) {
            val domainName = parts.firstOrNull().orEmpty()
            val consonantRatio = domainName.count { it in "bcdfghjklmnpqrstvwxyz" }.toFloat() /
                    domainName.length.coerceAtLeast(1)
            if (domainName.length > 6 && consonantRatio > 0.75f) {
                flags.add("Domain appears randomly generated")
                score += 20
            }
        }

        // 14. Smishing pattern
        if (messageText != null) {
            var smishingMatched = false
            SMISHING_PATTERNS.forEach { pattern ->
                if (!smishingMatched && pattern.containsMatchIn(messageText)) {
                    flags.add("Message matches known smishing/scam pattern")
                    score += 40
                    smishingMatched = true
                }
            }
        }

        // 15. Dash-heavy domain
        if (!isOfficialDomain && domain.count { it == '-' } >= 3) {
            flags.add("Domain contains excessive hyphens — common in fake sites")
            score += 15
        }

        // 16. Typosquatting (Levenshtein)
        if (!isOfficialDomain) {
            val cleanDomain = domain.removePrefix("www.")
            val domainName = cleanDomain.split(".").firstOrNull().orEmpty()
            PROTECTED_DOMAINS.forEach { brand ->
                val distance = levenshtein(domainName, brand)
                if (domainName.length >= 4 && distance in 1..2 && domainName != brand) {
                    flags.add("Lookalike domain — very similar to \"${brand.uppercase()}\" (possible typosquatting)")
                    score += 50
                }
            }
        }

        // 17. Dangerous file extensions (match only at a real filename boundary, so ".bat"
        //     no longer fires on "account.battle.net" / ".exe" on "api.execute.com")
        if (!isOfficialDomain) {
            DANGEROUS_EXTENSIONS.forEach { ext ->
                if (urlReferencesFileType(urlLower, ext)) {
                    flags.add("Dangerous file type in URL: \"$ext\" — possible malware delivery")
                    score += 40
                }
            }
        }

        score = score.coerceIn(0, 100)

        val threatLevel = when {
            score >= 60 -> ThreatLevel.DANGER
            score >= 25 -> ThreatLevel.SUSPICIOUS
            else -> ThreatLevel.SAFE
        }

        return ScanResult(
            url = url,
            threatLevel = threatLevel,
            riskScore = score,
            category = "Scanning...", // Will be updated by the scoring pipeline
            flags = flags.distinct(),
            sourceApp = "",
            senderInfo = ""
        )
    }

    private fun extractPath(url: String): String = try {
        val withoutScheme = url.removePrefix("http://").removePrefix("https://")
        val idx = withoutScheme.indexOf('/')
        if (idx != -1) withoutScheme.substring(idx) else ""
    } catch (_: Exception) { "" }
}
