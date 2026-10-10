package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.Arrays;
import java.util.List;

import javassist.CannotCompileException;
import javassist.CtBehavior;

public class LoopIterations extends AbstractJavassistTool {

    private static String[] probeMethodNames = new String[256];
    private static int[] probePositions = new int[256];
    private static long[] probeCounters = new long[256];
    private static int probeCount = 0;

    public LoopIterations(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    private static boolean isLoopHeader(BasicBlock block) {
        for (int entrance : block.entrances) {
            if (entrance > block.getPosition()) {
                return true;
            }
        }
        return false;
    }

    private static void ensureCapacity(int minCapacity) {
        if (minCapacity <= probeMethodNames.length) {
            return;
        }

        int newCapacity = probeMethodNames.length;
        while (newCapacity < minCapacity) {
            newCapacity *= 2;
        }

        probeMethodNames = Arrays.copyOf(probeMethodNames, newCapacity);
        probePositions = Arrays.copyOf(probePositions, newCapacity);
        probeCounters = Arrays.copyOf(probeCounters, newCapacity);
    }

    private static synchronized int registerProbe(String methodLongName, int position) {
        int probeId = probeCount;
        ensureCapacity(probeId + 1);

        probeMethodNames[probeId] = methodLongName;
        probePositions[probeId] = position;
        probeCounters[probeId] = 0L;

        probeCount++;
        return probeId;
    }

    public static void visitLoopHeader(int probeId) {
        probeCounters[probeId]++;
    }

    public static void printStatistics() {
        long totalLoopIterations = 0;

        for (int probeId = 0; probeId < probeCount; probeId++) {
            if (probeCounters[probeId] == 0L) {
                continue;
            }
            long loopIterations = Math.max(0L, probeCounters[probeId] - 1L);
            totalLoopIterations += loopIterations;
            System.out.println(String.format("[%s] %s loop header %s was called %s times",
                    LoopIterations.class.getSimpleName(),
                    probeMethodNames[probeId],
                    probePositions[probeId],
                    loopIterations));
        }

        System.out.println(String.format("[%s] Total loop iterations: %s",
                LoopIterations.class.getSimpleName(),
                totalLoopIterations));
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);

        if (behavior.getName().equals("main")) {
            behavior.insertAfter(String.format("%s.printStatistics();", LoopIterations.class.getName()));
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);

        if (!isLoopHeader(block)) {
            return;
        }

        int probeId = registerProbe(block.getBehavior().getLongName(), block.getPosition());
        block.getBehavior().insertAt(block.getLine(), String.format(
                "%s.visitLoopHeader(%s);",
                LoopIterations.class.getName(),
                probeId));
    }
}
