package com.fongmi.android.tv.setting;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlayerSettingTest {

    @Test
    public void nativeVideoOutput_includesNativePlayers() {
        assertFalse(PlayerSetting.useNativeVideoOutput(PlayerSetting.EXO));
        assertTrue(PlayerSetting.useNativeVideoOutput(PlayerSetting.IJK));
        assertTrue(PlayerSetting.useNativeVideoOutput(PlayerSetting.SYSTEM));
        assertTrue(PlayerSetting.useNativeVideoOutput(PlayerSetting.MPV));
    }

    @Test
    public void nativeVideoOutput_forcesSurfaceRender() {
        assertEquals(0, PlayerSetting.getRender(PlayerSetting.IJK));
        assertEquals(0, PlayerSetting.getRender(PlayerSetting.SYSTEM));
        assertEquals(0, PlayerSetting.getRender(PlayerSetting.MPV));
    }

    @Test
    public void nextPlayer_exoOnlyAlwaysReturnsExo() {
        assertEquals(PlayerSetting.EXO, PlayerSetting.nextPlayer(PlayerSetting.EXO));
        assertEquals(PlayerSetting.EXO, PlayerSetting.nextPlayer(PlayerSetting.IJK));
    }

    @Test
    public void kernelOrder_exoOnlyRankAndPositionRoundTrip() {
        assertArrayEquals(new int[]{PlayerSetting.EXO}, PlayerSetting.KERNEL_ORDER);
        assertEquals(0, PlayerSetting.kernelRank(PlayerSetting.EXO));
        assertEquals(0, PlayerSetting.kernelRank(PlayerSetting.IJK));
        for (int rank = 0; rank < PlayerSetting.kernelCount(); rank++) {
            assertEquals(rank, PlayerSetting.kernelRank(PlayerSetting.kernelAt(rank)));
        }
    }

    @Test
    public void kernelAt_clampsOutOfRangeRowsToExo() {
        assertEquals(PlayerSetting.EXO, PlayerSetting.kernelAt(-1));
        assertEquals(PlayerSetting.EXO, PlayerSetting.kernelAt(PlayerSetting.kernelCount()));
    }

    @Test
    public void orderKernels_exoOnlyPicksExoLabel() {
        String[] labels = {"EXO", "IJK", "System", "MPV"};
        assertArrayEquals(new String[]{"EXO"}, PlayerSetting.orderKernels(labels));
    }

    @Test
    public void firstUntriedPlayer_exoOnlyReturnsExoUntilTried() {
        boolean[] tried = new boolean[PlayerSetting.EXO + 1];
        assertEquals(PlayerSetting.EXO, PlayerSetting.firstUntriedPlayer(tried));
        tried[PlayerSetting.EXO] = true;
        assertEquals(PlayerSetting.NONE, PlayerSetting.firstUntriedPlayer(tried));
    }

    @Test
    public void kernelIndexSize_coversEveryKernelConstant() {
        int size = PlayerSetting.kernelIndexSize();
        for (int kernel : PlayerSetting.KERNEL_ORDER) {
            assertTrue("内核常量 " + kernel + " 必须能作为下标写进长度 " + size + " 的数组", kernel < size);
        }
    }

    @Test
    public void firstUntriedPlayer_treatsUntrackableKernelsAsTriedSoFallbackTerminates() {
        // 标记表短于内核常量时，越界内核记不进去；若当成未试过返回，回退会拿到同一个内核不停循环。
        assertEquals(PlayerSetting.NONE, PlayerSetting.firstUntriedPlayer(new boolean[0]));
        boolean[] onlyExoTrackable = new boolean[PlayerSetting.EXO + 1];
        assertEquals(PlayerSetting.EXO, PlayerSetting.firstUntriedPlayer(onlyExoTrackable));
        onlyExoTrackable[PlayerSetting.EXO] = true;
        assertEquals(PlayerSetting.NONE, PlayerSetting.firstUntriedPlayer(onlyExoTrackable));
    }

    @Test
    public void firstUntriedPlayer_startsFromExoWhateverKernelFailed() {
        boolean[] fromSystem = new boolean[PlayerSetting.MPV + 1];
        fromSystem[PlayerSetting.SYSTEM] = true;
        assertEquals(PlayerSetting.EXO, PlayerSetting.firstUntriedPlayer(fromSystem));

        boolean[] fromIjk = new boolean[PlayerSetting.MPV + 1];
        fromIjk[PlayerSetting.IJK] = true;
        assertEquals(PlayerSetting.EXO, PlayerSetting.firstUntriedPlayer(fromIjk));

        boolean[] fromExo = new boolean[PlayerSetting.MPV + 1];
        fromExo[PlayerSetting.EXO] = true;
        assertEquals(PlayerSetting.NONE, PlayerSetting.firstUntriedPlayer(fromExo));
    }

}
