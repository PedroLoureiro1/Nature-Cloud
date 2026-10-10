package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.Arrays;
import java.util.List;

import javassist.CannotCompileException;
import javassist.CtBehavior;

public class BasicBlocks extends AbstractJavassistTool {

    private static String[] probeMethodNames = new String[256];
    private static int[] probePositions = new int[256];
    private static long[] probeCounters = new long[256];
    private static int probeCount = 0;

    public BasicBlocks(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
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

    public static void visitBasicBlock(int probeId) {
        probeCounters[probeId]++;
    }

    public static void printStatistics() {
        for (int probeId = 0; probeId < probeCount; probeId++) {
            if (probeCounters[probeId] == 0L) {
                continue;
            }
            System.out.println(String.format(
                    "[%s] %s basic block %s was called %s times",
                    BasicBlocks.class.getSimpleName(),
                    probeMethodNames[probeId],
                    probePositions[probeId],
                    probeCounters[probeId]));
        }
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);

        if (behavior.getName().equals("main")) {
            behavior.insertAfter(String.format("%s.printStatistics();", BasicBlocks.class.getName()));
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);
        for (int incoming : block.entrances) {
            System.out.println(String.format("[%s] Basic block %s has an outgoing edge to basic block %s",
                    BasicBlocks.class.getSimpleName(), incoming, block.getPosition()));
        }

        int probeId = registerProbe(block.getBehavior().getLongName(), block.getPosition());
        block.behavior.insertAt(block.line, String.format("%s.visitBasicBlock(%s);", BasicBlocks.class.getName(), probeId));
    }
}
