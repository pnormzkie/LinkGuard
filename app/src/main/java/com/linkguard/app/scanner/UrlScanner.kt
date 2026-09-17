package com.linkguard.app.scanner

import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import com.linkguard.app.util.DomainExtractor
import com.linkguard.app.util.KnownDomains
import java.net.URI
import java.util.regex.Pattern

internal val DANGEROUS_FILE_EXTENSIONS = listOf(
    ".exe", ".apk", ".bat", ".cmd", ".msi", ".ps1",
    ".vbs", ".jar", ".scr", ".pif", ".reg",
    ".zip", ".rar", ".7z", ".docm", ".xlsm", ".pptm"
)

internal enum class LocalHeuristicStrength { WEAK, MEDIUM, STRONG, CRITICAL }

internal data class LocalHeuristicFinding(
    val ruleId: String,
    val title: String,
    val strength: LocalHeuristicStrength,
    val score: Int,
    val legacyScore: Int
)

// ─── URL Extractor ────────────────────────────────────────────────────────────

object UrlExtractor {

    private val URL_PATTERN: Pattern = Pattern.compile(
        "(?<![\\p{L}\\p{N}_])(https?://[^\\s<>\\\"']+)",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val BARE_DOMAIN_PATTERN: Pattern = Pattern.compile(
        "(?<![\\p{L}\\p{N}_@])(" +
            "(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?\\.)+" +
            "(?:[\\p{L}]{2,63}|xn--[a-z0-9-]{2,59})" +
            "(?::\\d{1,5})?(?:/[^\\s<>\\\"']*)?" +
            ")",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    fun extractUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        val explicitRanges = mutableListOf<IntRange>()

        val matcher1 = URL_PATTERN.matcher(text)
        while (matcher1.find()) {
            val candidate = normalizeCandidate(matcher1.group(1) ?: continue, addScheme = false)
                ?: continue
            urls.add(candidate)
            explicitRanges.add(matcher1.start(1) until matcher1.end(1))
        }

        val matcher2 = BARE_DOMAIN_PATTERN.matcher(text)
        while (matcher2.find()) {
            val range = matcher2.start(1) until matcher2.end(1)
            if (explicitRanges.any { it.first < range.last + 1 && range.first < it.last + 1 }) continue

            val candidate = normalizeCandidate(matcher2.group(1) ?: continue, addScheme = true)
                ?: continue
            urls.add(candidate)
        }

        return urls.distinctBy { it.lowercase() }
    }

    /**
     * Removes prose punctuation without damaging balanced delimiters that legitimately belong
     * to a URL. Trust decisions deliberately happen later in the detection pipeline: extraction
     * must never suppress a path merely because its host is well known.
     */
    private fun normalizeCandidate(raw: String, addScheme: Boolean): String? {
        var value = raw.trim()
        while (value.lastOrNull() in TRAILING_SENTENCE_PUNCTUATION) value = value.dropLast(1)
        value = trimUnmatchedCloser(value, '(', ')')
        value = trimUnmatchedCloser(value, '[', ']')
        value = trimUnmatchedCloser(value, '{', '}')
        if (value.isBlank()) return null

        val normalized = if (addScheme) "https://$value" else value
        val host = DomainExtractor.extract(normalized) ?: return null
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return null
        return normalized
    }

    private fun trimUnmatchedCloser(value: String, opener: Char, closer: Char): String {
        var trimmed = value
        while (trimmed.endsWith(closer) && trimmed.count { it == closer } > trimmed.count { it == opener }) {
            trimmed = trimmed.dropLast(1)
        }
        return trimmed
    }

    private val TRAILING_SENTENCE_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', '\u2026')
}

// ─── Heuristic Scanner ────────────────────────────────────────────────────────

object HeuristicScanner {

    // A TLD is weak context, never a verdict by itself. Keep this list limited to suffixes
    // with elevated abuse risk; mainstream namespaces such as .io/.info/.shop are excluded.
    private val ELEVATED_RISK_TLDS = listOf(
        ".tk", ".ml", ".ga", ".cf", ".gq",
        ".xyz", ".top", ".click", ".download", ".cc", ".su",
        ".pw", ".vip", ".win", ".loan", ".buzz", ".icu", ".cyou"
    )

    private val URL_SHORTENERS = listOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly",
        "short.link", "rb.gy", "cutt.ly", "is.gd", "buff.ly",
        "tiny.cc", "shorte.st", "adf.ly", "bc.vc", "s.id",
        "v.gd", "clck.ru", "qr.ae", "to.ly", "x.co",
        "lnkd.in", "fb.me", "wa.me", "go2l.ink", "shorturl.at"
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
        Regex("(call|tumawag|tawag).{0,15}(now|agad|immediately).{0,25}(support|technician|microsoft|apple)", RegexOption.IGNORE_CASE),
        Regex("(?:download|install|i-?install).{0,30}(?:anydesk|teamviewer|quicksupport|remote desktop)", RegexOption.IGNORE_CASE),
        Regex("(?:like|follow|review|rate|subscribe).{0,40}(?:task|commission|kita|earn|bayad|paid)", RegexOption.IGNORE_CASE),
        Regex("(?:guaranteed|sigurado|sure).{0,30}(?:return|profit|kita|income).{0,30}(?:invest|deposit|cash in|mag-?invest)", RegexOption.IGNORE_CASE),
        Regex("(?:warrant|arrest|aresto|kaso|investigation).{0,50}(?:pay|bayad|deposit|transfer|legal fee|penalty)", RegexOption.IGNORE_CASE)
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
    private fun urlReferencesFileType(pathAndQuery: String, ext: String): Boolean =
        Regex(Regex.escape(ext) + "(?![a-z0-9])").containsMatchIn(pathAndQuery)

    /**
     * Only the path and query can identify a downloaded file. Looking at the full URL would
     * misread a hostname such as "example.zip" or fragment-only display text as a download.
     */
    private fun extractFileReference(url: String): String = runCatching {
        val uri = URI(url)
        buildString {
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let {
                append('?')
                append(it)
            }
        }
    }.getOrElse {
        extractPath(url).substringBefore('#')
    }

    private data class Analysis(
        val findings: List<LocalHeuristicFinding>,
        val legacyScore: Int
    )

    private fun analyze(url: String, messageText: String?): Analysis {
        val findings = mutableListOf<LocalHeuristicFinding>()
        var score = 0

        fun addFinding(
            ruleId: String,
            title: String,
            legacyScore: Int,
            strength: LocalHeuristicStrength,
            activeScore: Int
        ) {
            findings += LocalHeuristicFinding(ruleId, title, strength, activeScore, legacyScore)
            score += legacyScore
        }

        val urlLower = url.lowercase()
        val domain = DomainExtractor.extract(urlLower) ?: urlLower
        // Inspect the actual characters, not the ASCII encoding, for local lookalike rules.
        // Keep domain and the original URL unchanged for trust checks and network/navigation.
        val unicodeDomain = runCatching { java.net.IDN.toUnicode(domain) }.getOrDefault(domain)
        val urlPath = extractPath(urlLower)
        val fileReference = extractFileReference(urlLower)
        // Official if the host matches a trusted domain exactly OR is a subdomain of one.
        // The leading dot in ".$it" prevents suffix spoofing (e.g. "secure-google.com" is
        // NOT a subdomain of "google.com").
        val isOfficialDomain = KnownDomains.isTrusted(domain)

        // 1. HTTP
        if (!isOfficialDomain && urlLower.startsWith("http://")) {
            addFinding("UNENCRYPTED_HTTP", "Unencrypted HTTP connection", 30, LocalHeuristicStrength.WEAK, 15)
        }

        // 2. Elevated-risk TLD (one weak signal only; a suffix never decides the verdict alone)
        if (!isOfficialDomain) {
            ELEVATED_RISK_TLDS.firstOrNull { domain.endsWith(it) }?.let { tld ->
                addFinding(
                    "ELEVATED_RISK_TLD",
                    "Domain extension has elevated abuse risk ($tld)",
                    15,
                    LocalHeuristicStrength.WEAK,
                    15
                )
            }
        }

        // 3. URL Shorteners (always check — shorteners can wrap official domains)
        URL_SHORTENERS.forEach { shortener ->
            if (domain == shortener || domain.endsWith(".$shortener")) {
                addFinding(
                    "URL_SHORTENER",
                    "URL shortener detected — destination hidden",
                    30,
                    LocalHeuristicStrength.MEDIUM,
                    25
                )
            }
        }

        // 4. Phishing keywords (whole-token match so "login" doesn't fire on "/bloginfo"
        //    and "last" doesn't fire on "elastic")
        if (!isOfficialDomain) {
            // Keyword hits are one evidence family. A marketing URL containing several terms
            // such as /promo/gift/reward must not accumulate enough duplicate evidence to turn
            // suspicious by itself. Prefer the domain match because it is the stronger context.
            val domainKeyword = PHISHING_KEYWORDS.firstOrNull { containsKeyword(domain, it) }
            val pathKeyword = PHISHING_KEYWORDS.firstOrNull { containsKeyword(urlPath, it) }
            when {
                domainKeyword != null -> addFinding(
                    "PHISHING_KEYWORD_DOMAIN",
                    "Phishing keyword in domain: \"$domainKeyword\"",
                    15,
                    LocalHeuristicStrength.WEAK,
                    10
                )
                pathKeyword != null -> addFinding(
                    "PHISHING_KEYWORD_PATH",
                    "Phishing keyword in URL path: \"$pathKeyword\"",
                    10,
                    LocalHeuristicStrength.WEAK,
                    10
                )
            }
        }

        // 5. Brand spoofing (brand as a full label or glued to a phishing affix, so
        //    "verifypaypal.com" is caught but "startups.com"/"pineapple.com" are not)
        if (!isOfficialDomain) {
            ALL_BRANDS.forEach { brand ->
                if (looksLikeBrandSpoof(unicodeDomain, brand)) {
                    val officialList = OFFICIAL_DOMAINS[brand]
                        ?: listOf("$brand.com", "$brand.com.ph")
                    val isOfficial = officialList.any { domain == it || domain.endsWith(".$it") }
                    if (!isOfficial) {
                        addFinding(
                            "BRAND_SPOOF_${brand.filter { it.isLetterOrDigit() }}",
                            "Possible brand spoofing: \"${brand.uppercase()}\"",
                            40,
                            LocalHeuristicStrength.STRONG,
                            40
                        )
                    }
                }
            }
        }

        // 6. Homograph / number-substitution
        if (!isOfficialDomain) {
            val normalizedDomain = unicodeDomain.map { HOMOGRAPH_MAP[it] ?: it }.joinToString("")
            if (normalizedDomain != unicodeDomain) {
                ALL_BRANDS.forEach { brand ->
                    if (normalizedDomain.contains(brand)) {
                        addFinding(
                            "NUMBER_SUBSTITUTION_LOOKALIKE",
                            "Lookalike domain detected — uses numbers as letters",
                            45,
                            LocalHeuristicStrength.STRONG,
                            45
                        )
                    }
                }
            }
        }

        // 6b. IDN / homoglyph — punycode (xn--) or mixed-script labels impersonate real sites.
        //     ASCII digit-substitution is rule #6; this catches Unicode lookalikes that bypass it.
        if (!isOfficialDomain) {
            val labels = domain.split(".")
            if (labels.any { it.startsWith("xn--") }) {
                addFinding(
                    "PUNYCODE_DOMAIN",
                    "Internationalized (punycode) domain — can disguise the real name",
                    15,
                    LocalHeuristicStrength.WEAK,
                    10
                )
                if (ALL_BRANDS.any { unicodeDomain.contains(it, ignoreCase = true) }) {
                    addFinding(
                        "PUNYCODE_BRAND_LOOKALIKE",
                        "Lookalike domain — punycode mimics a known brand",
                        45,
                        LocalHeuristicStrength.STRONG,
                        45
                    )
                }
            }
            if (hasMixedScript(unicodeDomain)) {
                addFinding(
                    "MIXED_SCRIPT_DOMAIN",
                    "Domain mixes character sets — possible homoglyph spoofing",
                    45,
                    LocalHeuristicStrength.STRONG,
                    45
                )
            }
        }

        // 7. Excessive subdomains
        val parts = domain.split(".")
        if (!isOfficialDomain && parts.size > 4) {
            addFinding(
                "EXCESSIVE_SUBDOMAINS",
                "Unusual number of subdomains",
                15,
                LocalHeuristicStrength.WEAK,
                15
            )
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
                        addFinding(
                            "BRAND_IN_SUBDOMAIN_${brand.filter { it.isLetterOrDigit() }}",
                            "Brand name used in subdomain — classic phishing trick",
                            50,
                            LocalHeuristicStrength.STRONG,
                            50
                        )
                    }
                }
            }
        }

        // 9. IP address
        if (domain.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            addFinding(
                "IP_ADDRESS_HOST",
                "IP address used instead of domain name",
                30,
                LocalHeuristicStrength.MEDIUM,
                25
            )
        }

        // 10. Long URL
        if (!isOfficialDomain && url.length > 200) {
            addFinding("LONG_URL", "Unusually long URL", 10, LocalHeuristicStrength.WEAK, 10)
        }

        // 11. Excessive encoding
        if (!isOfficialDomain && url.count { it == '%' } > 5) {
            addFinding(
                "EXCESSIVE_URL_ENCODING",
                "Excessive URL encoding — possible obfuscation",
                20,
                LocalHeuristicStrength.WEAK,
                15
            )
        }

        // 12. Gibberish domain
        if (!isOfficialDomain) {
            val domainName = parts.firstOrNull().orEmpty()
            val consonantRatio = domainName.count { it in "bcdfghjklmnpqrstvwxyz" }.toFloat() /
                    domainName.length.coerceAtLeast(1)
            if (domainName.length > 6 && consonantRatio > 0.75f) {
                addFinding(
                    "GIBBERISH_DOMAIN",
                    "Domain appears randomly generated",
                    20,
                    LocalHeuristicStrength.WEAK,
                    15
                )
            }
        }

        // 13. Smishing pattern
        if (messageText != null) {
            var smishingMatched = false
            SMISHING_PATTERNS.forEach { pattern ->
                if (!smishingMatched && pattern.containsMatchIn(messageText)) {
                    addFinding(
                        "SMISHING_MESSAGE_PATTERN",
                        "Message matches known smishing/scam pattern",
                        40,
                        LocalHeuristicStrength.MEDIUM,
                        25
                    )
                    smishingMatched = true
                }
            }
        }

        // 14. Dash-heavy domain
        if (!isOfficialDomain && unicodeDomain.count { it == '-' } >= 3) {
            addFinding(
                "DASH_HEAVY_DOMAIN",
                "Domain contains excessive hyphens — common in fake sites",
                15,
                LocalHeuristicStrength.WEAK,
                15
            )
        }

        // 15. Typosquatting (Levenshtein)
        if (!isOfficialDomain) {
            val cleanDomain = unicodeDomain.removePrefix("www.")
            val domainName = cleanDomain.split(".").firstOrNull().orEmpty()
            PROTECTED_DOMAINS.forEach { brand ->
                val distance = levenshtein(domainName, brand)
                if (domainName.length >= 4 && distance in 1..2 && domainName != brand) {
                    addFinding(
                        "TYPOSQUAT_${brand.filter { it.isLetterOrDigit() }}",
                        "Lookalike domain — very similar to \"${brand.uppercase()}\" (possible typosquatting)",
                        50,
                        LocalHeuristicStrength.STRONG,
                        50
                    )
                }
            }
        }

        // 16. Dangerous file extensions (match only at a real filename boundary, so ".bat"
        //     no longer fires on "account.battle.net" / ".exe" on "api.execute.com")
        DANGEROUS_FILE_EXTENSIONS.forEach { ext ->
            if (urlReferencesFileType(fileReference, ext)) {
                addFinding(
                    "DANGEROUS_FILE_TYPE_${ext.removePrefix(".").uppercase()}",
                    "Dangerous file type in URL: \"$ext\" — possible malware delivery",
                    if (isOfficialDomain) 10 else 40,
                    if (isOfficialDomain) LocalHeuristicStrength.WEAK else LocalHeuristicStrength.MEDIUM,
                    if (isOfficialDomain) 10 else 25
                )
            }
        }

        score = score.coerceIn(0, 100)
        return Analysis(findings.distinctBy { it.ruleId }, score)
    }

    internal fun findingsWithContext(url: String, messageText: String?): List<LocalHeuristicFinding> =
        analyze(url, messageText).findings

    fun scan(url: String): ScanResult = scanWithContext(url, null)

    fun scanWithContext(url: String, messageText: String?): ScanResult {
        val analysis = analyze(url, messageText)
        val score = analysis.legacyScore.coerceIn(0, 100)
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
            flags = analysis.findings.map { it.title },
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
