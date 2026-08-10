package com.assethub.notifier

import java.util.regex.Pattern

/** 파싱된 거래 1건. amount 는 원 단위, type 은 "in"|"out". */
data class Tx(
    val source: String,       // "allbank"|"kbank"|"kakaobank"|"samsungcard"|"hanacard"
    val account: String,      // 은행/카드 표시명
    val type: String,         // "in"|"out"
    val amount: Long,
    val counterparty: String,
    val raw: String,
    val dedupKey: String,
    val acctDigits: String = "",
    val postTime: Long = 0L,
    val isCard: Boolean = false   // true = 카드 승인 (예정지출, 잔액 변동 없음)
)

object TxParser {

    private val BANKS = mapOf(
        "com.nonghyup.nhallonebank"       to Pair("allbank",     "올원뱅크"),
        "com.nh.cashcardapp"              to Pair("allbank",     "올원뱅크"),
        "com.nonghyup.allonebank"         to Pair("allbank",     "올원뱅크"),
        "com.kbankwith.smartbank"         to Pair("kbank",       "케이뱅크"),
        "com.kakaobank.channel"           to Pair("kakaobank",   "카카오뱅크"),
        "com.kakao.talk"                  to Pair("kakaotalk",   "카카오톡"),   // 삼성카드 알림톡
        // SMS 앱 (하나카드 등 문자 승인)
        "com.android.mms"                 to Pair("sms",         "문자"),
        "com.samsung.android.messaging"   to Pair("sms",         "문자"),
        "com.google.android.apps.messaging" to Pair("sms",       "문자")
    )

    fun isBank(pkg: String) = BANKS.containsKey(pkg)

    private val EXCLUDE_WORDS = listOf("모임통장", "모임 통장")
    private val amountP = Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\s*원")
    private val inWords  = listOf("입금", "받으심", "이체받", "환급")
    private val outWords = listOf("출금", "결제", "이체", "송금", "납부", "지불", "인출", "승인")

    fun parse(pkg: String, title: String?, text: String?, postTime: Long): Tx? {
        val info = BANKS[pkg] ?: return null
        val body = listOfNotNull(title, text).joinToString("\n").trim()
        if (body.isEmpty()) return null
        if (EXCLUDE_WORDS.any { body.contains(it) }) return null

        return when (info.first) {
            "kakaotalk" -> parseSamsungCardKakao(body, postTime)
            "sms"       -> parseSms(body, postTime)
            else        -> parseBank(info, pkg, body, postTime)
        }
    }

    // ── 은행 계좌 알림 ──────────────────────────────────────────
    private fun parseBank(info: Pair<String,String>, pkg: String, body: String, postTime: Long): Tx? {
        if (body.contains("승인취소") || body.contains("취소승인")) return null
        val m = amountP.matcher(body)
        if (!m.find()) return null
        val amount = m.group(1)!!.replace(",", "").toLongOrNull() ?: return null
        if (amount <= 0) return null
        val type = when {
            inWords.any  { body.contains(it) } -> "in"
            outWords.any { body.contains(it) } -> "out"
            else -> "out"
        }
        val cp = Regex("([가-힣A-Za-z0-9()]+)\\s*님").find(body)?.groupValues?.get(1)
            ?: Regex("(?:결제|출금|입금)\\s*[:\\-]?\\s*([가-힣A-Za-z0-9().]+)").find(body)?.groupValues?.get(1)
            ?: ""

        // 계좌번호 뒷자리 추출 — 금액에서 나온 숫자를 계좌로 오인하지 않도록 엄격하게.
        // 우선순위: 마스킹 패턴(***1234, 356-****-7352-33) > 괄호 안 4자리 > 없음
        val acctDigits = run {
            // 1) 금액 부분을 본문에서 제거해 오탐 차단
            val stripped = body.replace(Regex("[0-9,]+\\s*원"), " ")
            // 2) 마스킹 뒤 4자리: "***7352", "*7352", "356-****-7352-33"
            val masked = Regex("[*\\-]\\s*(\\d{4})(?!\\d)").findAll(stripped)
                .map { it.groupValues[1] }.distinct().toList()
            if (masked.isNotEmpty()) masked.joinToString(",")
            else {
                // 3) 괄호 안 4자리: "(7352)"
                val paren = Regex("\\((\\d{4})\\)").findAll(stripped)
                    .map { it.groupValues[1] }.distinct().toList()
                if (paren.isNotEmpty()) paren.joinToString(",")
                else ""   // 후보 없음 → 서버가 이름으로만 매칭 (오적용 방지)
            }
        }
        val bucket = postTime / 60000
        val dedup  = "${pkg}_${amount}_${type}_${bucket}_${body.take(20).hashCode()}"
        return Tx(info.first, info.second, type, amount, cp, body, dedup, acctDigits, postTime, isCard = false)
    }

    // ── 카카오톡 삼성카드 승인 알림 ────────────────────────────
    private fun parseSamsungCardKakao(body: String, postTime: Long): Tx? {
        // 카카오톡으로는 여러 채널 알림이 오므로, 삼성카드 승인 형식만 처리한다.
        // (카카오뱅크 알림톡은 카카오뱅크 앱 알림과 중복되므로 여기서 제외)
        val samsungPattern = Regex("삼성\\d{4}승인")
        if (!samsungPattern.containsMatchIn(body)) return null
        if (body.contains("승인취소") || body.contains("취소")) return null

        val cardDigits = samsungPattern.find(body)?.value
            ?.let { Regex("\\d{4}").find(it)?.value } ?: ""
        val amountMatch = Regex("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)원").find(body) ?: return null
        val amount = amountMatch.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        if (amount <= 0) return null
        val merchant = Regex("\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}\\s+(.+?)(?:\\n|$)").find(body)
            ?.groupValues?.get(1)?.trim() ?: ""
        val bucket = postTime / 60000
        val dedup  = "samsungcard_${cardDigits}_${amount}_${bucket}"
        return Tx("samsungcard", "삼성카드", "out", amount, merchant, body, dedup, cardDigits, postTime, isCard = true)
    }

    // ── SMS 하나카드 승인 알림 ──────────────────────────────────
    // 형식: "금액 9,900원 카드 하나6*0* 사용처 주식회사레이모웍스 거래시간 08/08 12:00 누적금액 ..."
    // 또는 구조화된 형식 (개행 포함)
    private fun parseSms(body: String, postTime: Long): Tx? {
        // 하나카드 승인 판별
        val isHana = body.contains("하나") && (body.contains("승인") || body.contains("카드"))
        if (!isHana) return null
        if (body.contains("승인취소") || body.contains("취소")) return null

        // 금액: "금액 9,900원" 또는 "9,900원"
        val amountMatch = Regex("금액[\\s:]*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)원")
            .find(body)
            ?: Regex("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)원").find(body)
            ?: return null
        val amount = amountMatch.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        if (amount <= 0) return null

        // 카드 끝 번호: "하나6*0*" → 숫자만 뽑기
        val cardDigits = Regex("하나([0-9*]+)").find(body)?.groupValues?.get(1)
            ?.replace("*", "")?.takeLast(4) ?: ""

        // 사용처
        val merchant = Regex("사용처[\\s:]*([가-힣A-Za-z0-9()\\s(주)]+?)(?:\\s*거래|\\s*누적|\\n|$)").find(body)
            ?.groupValues?.get(1)?.trim() ?: ""

        val bucket = postTime / 60000
        val dedup  = "hanacard_${cardDigits}_${amount}_${bucket}"
        return Tx("hanacard", "하나카드", "out", amount, merchant, body, dedup, cardDigits, postTime, isCard = true)
    }
}
