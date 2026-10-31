package sleepy.autobuilder.autobuild.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TravelSupervisorTest {
    @Test
    void arrivesWithinTolerance() {
        TravelSupervisor supervisor = new TravelSupervisor(200, 2, 3.0);
        supervisor.begin(0);
        assertEquals(TravelSupervisor.Phase.ARRIVED, supervisor.tick(10, 2.5, true, false).phase());
    }

    @Test
    void timeoutRetriesThenFailsAfterLimit() {
        TravelSupervisor supervisor = new TravelSupervisor(20, 2, 3.0);
        supervisor.begin(0);
        TravelSupervisor.Status first = supervisor.tick(21, 100, true, false);
        assertEquals(TravelSupervisor.Phase.RETRY, first.phase());
        assertEquals(2, first.attempt());

        supervisor.retry(21);
        TravelSupervisor.Status second = supervisor.tick(42, 100, true, false);
        assertEquals(TravelSupervisor.Phase.FAILED, second.phase());
        assertTrue(second.message().contains("exhausted"));
    }

    @Test
    void explicitFailureCanRecover() {
        TravelSupervisor supervisor = new TravelSupervisor(200, 2, 3.0);
        supervisor.begin(0);
        assertEquals(TravelSupervisor.Phase.RETRY, supervisor.tick(1, 100, true, true).phase());
        supervisor.retry(1);
        assertEquals(TravelSupervisor.Phase.TRAVELING, supervisor.status(2).phase());
        assertEquals(TravelSupervisor.Phase.ARRIVED, supervisor.tick(3, 1, true, false).phase());
    }

    @Test
    void stalledTravelIsDetected() {
        TravelSupervisor supervisor = new TravelSupervisor(500, 2, 3.0);
        supervisor.begin(0);
        assertEquals(TravelSupervisor.Phase.RETRY, supervisor.tick(21, 100, false, false).phase());
        supervisor.retry(21);
        assertEquals(TravelSupervisor.Phase.FAILED, supervisor.tick(42, 100, false, false).phase());
    }

    @Test
    void configuredStallGraceAllowsPathCalculationPauses() {
        TravelSupervisor supervisor = new TravelSupervisor(500, 2, 3.0, 60);
        supervisor.begin(0);
        assertEquals(TravelSupervisor.Phase.TRAVELING,
                supervisor.tick(30, 100, false, false).phase());
        assertEquals(TravelSupervisor.Phase.TRAVELING,
                supervisor.tick(60, 100, false, false).phase());
        assertEquals(TravelSupervisor.Phase.RETRY,
                supervisor.tick(61, 100, false, false).phase());
    }

    @Test
    void activePathOrMovementPreventsAStall() {
        TravelSupervisor supervisor = new TravelSupervisor(500, 2, 3.0, 20);
        supervisor.begin(0);
        assertEquals(TravelSupervisor.Phase.TRAVELING,
                supervisor.tick(100, 100, true, false).phase());
        assertEquals(TravelSupervisor.Phase.TRAVELING,
                supervisor.tick(101, 100, true, false).phase());
    }

    @Test
    void stopIsTerminal() {
        TravelSupervisor supervisor = new TravelSupervisor(100, 2, 3.0);
        supervisor.begin(0);
        supervisor.stop();
        assertEquals(TravelSupervisor.Phase.STOPPED, supervisor.tick(1, 100, false, false).phase());
    }
}
