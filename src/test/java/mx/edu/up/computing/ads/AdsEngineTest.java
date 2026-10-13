package mx.edu.up.computing.ads;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdsEngineTest {

    @Test
    void sumSubMulDivSuccess() {
        AdsCalculatorEngine sum = new AdsCalculatorEngine(AdsService.SUM);
        AdsCalculatorEngine sub = new AdsCalculatorEngine(AdsService.SUB);
        AdsCalculatorEngine mul = new AdsCalculatorEngine(AdsService.MUL);
        AdsCalculatorEngine div = new AdsCalculatorEngine(AdsService.DIV);

        assertEquals(8.0, sum.compute(req(AdsMessageType.SUM_REQ, 5.0, 3.0)).value());
        assertEquals(2.0, sub.compute(req(AdsMessageType.SUB_REQ, 5.0, 3.0)).value());
        assertEquals(15.0, mul.compute(req(AdsMessageType.MUL_REQ, 5.0, 3.0)).value());
        assertEquals(2.5, div.compute(req(AdsMessageType.DIV_REQ, 5.0, 2.0)).value());
    }

    @Test
    void divisionByZeroIsExactNotEpsilon() {
        AdsCalculatorEngine div = new AdsCalculatorEngine(AdsService.DIV);
        AdsCalculatorEngine.AdsCalculationResult zeroR = div.compute(req(AdsMessageType.DIV_REQ, 7.0, 0.0));
        assertFalse(zeroR.isSuccess());
        assertEquals(AdsCalculatorEngine.STATUS_DIVISION_BY_ZERO, zeroR.status());
    }

    @Test
    void smallNonZeroDenominatorProducesQuotient() {
        // El parcial 1 usaba epsilon 1e-12 para tratar casi-cero como cero.
        // ADS exige comparación exacta: 1e-300 ES un denominador válido.
        AdsCalculatorEngine div = new AdsCalculatorEngine(AdsService.DIV);
        AdsCalculatorEngine.AdsCalculationResult r = div.compute(req(AdsMessageType.DIV_REQ, 1.0, 1e-300));
        assertTrue(r.isSuccess(), "1e-300 debe procesarse, no tratarse como cero");
        assertNotNull(r.value());
    }

    @Test
    void nonFiniteOperandsAreRejected() {
        AdsCalculatorEngine sum = new AdsCalculatorEngine(AdsService.SUM);
        AdsMessage nanReq = req(AdsMessageType.SUM_REQ, Double.NaN, 1.0);
        AdsCalculatorEngine.AdsCalculationResult r = sum.compute(nanReq);
        assertFalse(r.isSuccess());
        assertEquals(AdsCalculatorEngine.STATUS_NON_FINITE, r.status());

        AdsMessage infReq = req(AdsMessageType.SUM_REQ, 1.0, Double.POSITIVE_INFINITY);
        AdsCalculatorEngine.AdsCalculationResult r2 = sum.compute(infReq);
        assertFalse(r2.isSuccess());
        assertEquals(AdsCalculatorEngine.STATUS_NON_FINITE, r2.status());
    }

    @Test
    void nonFiniteResultIsRejected() {
        AdsCalculatorEngine mul = new AdsCalculatorEngine(AdsService.MUL);
        AdsMessage infReq = req(AdsMessageType.MUL_REQ, Double.MAX_VALUE, 2.0);
        AdsCalculatorEngine.AdsCalculationResult r = mul.compute(infReq);
        assertFalse(r.isSuccess());
        assertEquals(AdsCalculatorEngine.STATUS_NON_FINITE, r.status());
    }

    @Test
    void serviceMismatchIsRejected() {
        AdsCalculatorEngine sum = new AdsCalculatorEngine(AdsService.SUM);
        AdsMessage bad = req(AdsMessageType.DIV_REQ, 4.0, 2.0);
        AdsCalculatorEngine.AdsCalculationResult r = sum.compute(bad);
        assertEquals(AdsCalculatorEngine.STATUS_SERVICE_MISMATCH, r.status());
    }

    private AdsMessage req(AdsMessageType type, double a, double b) {
        AdsMessage m = AdsMessage.create(type);
        m.setService(AdsContentCodes.serviceOfRequest(type).name());
        m.setOperandA(a);
        m.setOperandB(b);
        return m;
    }
}