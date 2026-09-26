package com.myname.expensetracker.purchases;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Calendar;

/**
 * Runs the real rules file against example messages. The messages below are written in the style of
 * Saudi bank alerts; add your own bank's real formats here when you extend purchase_rules.json.
 */
public class PurchaseParserTest {
    private static PurchaseParser parser;
    private static long received; // 26 Sep 2026, 22:00 local time

    @BeforeClass
    public static void setUp() throws Exception {
        String json = new String(Files.readAllBytes(Paths.get("src/main/assets/purchase_rules.json")), StandardCharsets.UTF_8);
        parser = new PurchaseParser(new JSONObject(json));
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 26, 22, 0);
        received = c.getTimeInMillis();
    }

    private static Purchase ok(String text) {
        return ok(text, "SomeBank");
    }

    private static Purchase ok(String text, String sender) {
        PurchaseParser.Result r = parser.parse(text, sender, null, received);
        assertNotNull("expected a purchase but got " + r.reason, r.purchase);
        return r.purchase;
    }

    private static PurchaseParser.Reason ignored(String text) {
        PurchaseParser.Result r = parser.parse(text, "SomeBank", null, received);
        assertNull(r.purchase);
        return r.reason;
    }

    @Test
    public void englishLabeledLines() {
        Purchase p = ok("Purchase\nAmount: SAR 45.00\nAt: STARBUCKS RIYADH\nCard: mada *1234\nDate: 26/09/26 14:03\nBalance: SAR 3,210.55");
        assertEquals(45.00, p.amount, 0.001);
        assertEquals("SAR", p.currency);
        assertEquals("STARBUCKS RIYADH", p.merchant);
        assertEquals("1234", p.card);
        assertEquals("2026-09-26", p.date);
        assertEquals("14:03", p.time);
    }

    @Test
    public void arabicLabeledLines() {
        Purchase p = ok("شراء عبر نقاط البيع\nبطاقة: 4567;مدى\nمبلغ: 120.50 ريال\nلدى: هايبر بنده\nفي: 2026-09-26 10:15");
        assertEquals(120.50, p.amount, 0.001);
        assertEquals("SAR", p.currency);
        assertEquals("هايبر بنده", p.merchant);
        assertEquals("4567", p.card);
        assertEquals("2026-09-26", p.date);
        assertEquals("10:15", p.time);
    }

    @Test
    public void arabicSentence() {
        Purchase p = ok("تم خصم 89.00 ر.س من بطاقتك المنتهية ب 9876 لدى نون في 26/09 21:40");
        assertEquals(89.00, p.amount, 0.001);
        assertEquals("SAR", p.currency);
        assertEquals("نون", p.merchant);
        assertEquals("9876", p.card);
        assertEquals("2026-09-26", p.date);
        assertEquals("21:40", p.time);
    }

    @Test
    public void englishSentenceWithArabicDigitsAndBalance() {
        Purchase p = ok("POS purchase of SAR ٤٥٫٥٠ at Jarir Bookstore with card ending 1122 on 25-Sep-2026 at 9:05 PM. Avl bal SAR 1,000.00");
        assertEquals(45.50, p.amount, 0.001);
        assertEquals("Jarir Bookstore", p.merchant);
        assertEquals("1122", p.card);
        assertEquals("2026-09-25", p.date);
        assertEquals("21:05", p.time);
    }

    @Test
    public void balanceBeforeAmountIsSkipped() {
        Purchase p = ok("Avl Bal SAR 1,200.00. Purchase SAR 30.00 at KFC");
        assertEquals(30.00, p.amount, 0.001);
        assertEquals("KFC", p.merchant);
    }

    @Test
    public void foreignCurrencyAndNoDateUsesReceivedTime() {
        Purchase p = ok("Apple Pay purchase\n25 USD\nAMAZON.COM\nCard *5555");
        assertEquals(25, p.amount, 0.001);
        assertEquals("USD", p.currency);
        assertEquals("5555", p.card);
        assertEquals("2026-09-26", p.date);
        assertEquals("22:00", p.time);
    }

    @Test
    public void arabicAmountBeforeCurrencyWithBaPrefix() {
        Purchase p = ok("شراء انترنت بـ150 ريال سعودي من متجر أمازون بطاقة مدى *2468");
        assertEquals(150, p.amount, 0.001);
        assertEquals("SAR", p.currency);
        assertEquals("متجر أمازون", p.merchant);
        assertEquals("2468", p.card);
    }

    @Test
    public void otpMessagesAreNeverParsed() {
        assertEquals(PurchaseParser.Reason.OTP, ignored("Your OTP for purchase of SAR 45.00 at Amazon is 123456. Do not share it."));
        assertEquals(PurchaseParser.Reason.OTP, ignored("رمز التحقق لعملية شراء بمبلغ 45 ريال: 5521"));
        assertEquals(PurchaseParser.Reason.OTP, ignored("Use verification code 889900 to confirm your payment of SAR 12"));
    }

    @Test
    public void nonPurchasesAreIgnored() {
        assertEquals(PurchaseParser.Reason.NOT_PURCHASE, ignored("Refund of SAR 45.00 from STARBUCKS credited to card *1234"));
        assertEquals(PurchaseParser.Reason.NOT_PURCHASE, ignored("Purchase declined: insufficient balance. SAR 45.00 at X"));
        assertEquals(PurchaseParser.Reason.NOT_PURCHASE, ignored("حوالة واردة بمبلغ 500 ريال إلى حسابك"));
        assertEquals(PurchaseParser.Reason.NOT_PURCHASE, ignored("Your statement is ready"));
    }

    @Test
    public void bankSpecificPattern() {
        Purchase p = ok("Purchase of SAR 12.00 at CAFE X with card *3333 on 2026-09-26 08:30", "EXAMPLE-BANK");
        assertEquals(12.00, p.amount, 0.001);
        assertEquals("CAFE X", p.merchant);
        assertEquals("3333", p.card);
        assertEquals("08:30", p.time);
    }

    @Test
    public void senderNormalization() {
        assertEquals(PurchaseParser.normalizeSender("+966501234567"), PurchaseParser.normalizeSender("0501234567"));
        assertEquals(PurchaseParser.normalizeSender("AL-RAJHI Bank"), PurchaseParser.normalizeSender("alrajhibank"));
    }

    @Test
    public void duplicatesAcrossSmsAndNotification() {
        Purchase sms = ok("Purchase SAR 45.00 at STARBUCKS card *1234 26/09/26 14:03");
        Purchase notif = ok("Card purchase\nSAR 45.00 STARBUCKS\n*1234");
        notif.receivedAt = sms.receivedAt + 60_000;
        notif.time = null;
        assertTrue(PurchaseStore.isSame(sms, notif));
        Purchase other = ok("Purchase SAR 45.00 at KFC card *1234 26/09/26 14:03");
        assertFalse(PurchaseStore.isSame(sms, other));
    }
}
