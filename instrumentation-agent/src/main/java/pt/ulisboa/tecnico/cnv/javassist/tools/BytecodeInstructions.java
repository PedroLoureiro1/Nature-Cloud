package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.Arrays;
import java.util.List;

import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;

public class BytecodeInstructions extends AbstractJavassistTool {

    private static String[] methodNames = new String[128];
    private static long[] methodInstructionCounts = new long[128];
    private static int methodCount = 0;

    private static int[] probeMethodIds = new int[256];
    private static int[] probeInstructionWeights = new int[256];
    private static int probeCount = 0;

    private static long totalInstructions = 0;

    public BytecodeInstructions(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    private static int countBytecodeInstructions(CtBehavior behavior, int startPosition, int blockLength) throws Exception {
        CodeAttribute codeAttribute = behavior.getMethodInfo().getCodeAttribute();
        if (codeAttribute == null) {
            return 0;
        }

        int instructions = 0;
        CodeIterator iterator = codeAttribute.iterator();
        int endPosition = startPosition + blockLength;

        while (iterator.hasNext()) {
            int currentPosition = iterator.next();
            if (currentPosition >= startPosition && currentPosition < endPosition) {
                instructions++;
            }
        }

        return instructions;
    }

    private static void ensureMethodCapacity(int minCapacity) {
        if (minCapacity <= methodNames.length) {
            return;
        }

        int newCapacity = methodNames.length;
        while (newCapacity < minCapacity) {
            newCapacity *= 2;
        }

        methodNames = Arrays.copyOf(methodNames, newCapacity);
        methodInstructionCounts = Arrays.copyOf(methodInstructionCounts, newCapacity);
    }

    private static void ensureProbeCapacity(int minCapacity) {
        if (minCapacity <= probeMethodIds.length) {
            return;
        }

        int newCapacity = probeMethodIds.length;
        while (newCapacity < minCapacity) {
            newCapacity *= 2;
        }

        probeMethodIds = Arrays.copyOf(probeMethodIds, newCapacity);
        probeInstructionWeights = Arrays.copyOf(probeInstructionWeights, newCapacity);
    }

    private static int findOrCreateMethod(String methodLongName) {
        for (int i = 0; i < methodCount; i++) {
            if (methodLongName.equals(methodNames[i])) {
                return i;
            }
        }

        int methodId = methodCount;
        ensureMethodCapacity(methodId + 1);
        methodNames[methodId] = methodLongName;
        methodInstructionCounts[methodId] = 0L;
        methodCount++;
        return methodId;
    }

    private static synchronized int registerProbe(String methodLongName, int instructionCount) {
        int methodId = findOrCreateMethod(methodLongName);
        int probeId = probeCount;

        ensureProbeCapacity(probeId + 1);
        probeMethodIds[probeId] = methodId;
        probeInstructionWeights[probeId] = instructionCount;

        probeCount++;
        return probeId;
    }

    public static void printStatistics() {
        for (int methodId = 0; methodId < methodCount; methodId++) {
            if (methodInstructionCounts[methodId] == 0L) {
                continue;
            }
            System.out.println(String.format("[%s] %s has %s bytecode instructions",
                    BytecodeInstructions.class.getSimpleName(),
                    methodNames[methodId],
                    methodInstructionCounts[methodId]));
        }
        System.out.println(String.format("[%s] Total bytecode instructions: %s",
                BytecodeInstructions.class.getSimpleName(),
                totalInstructions));
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);

        if (behavior.getName().equals("main")) {
            behavior.insertAfter(String.format("%s.printStatistics();", BytecodeInstructions.class.getName()));
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);

        int instructionCount;

        try {
            instructionCount = countBytecodeInstructions(block.getBehavior(), block.getPosition(), block.getLength());
        } catch (Exception e) {
            throw new CannotCompileException(e);
        }

        if (instructionCount <= 0) {
            return;
        }

        int probeId = registerProbe(block.getBehavior().getLongName(), instructionCount);
        block.getBehavior().insertAt(block.getLine(), String.format(
            "%s.accumulateInstructions(%s);",
            BytecodeInstructions.class.getName(),
            probeId));
    }

    public static void accumulateInstructions(int probeId) {
        int methodId = probeMethodIds[probeId];
        int instructionCount = probeInstructionWeights[probeId];
        methodInstructionCounts[methodId] += instructionCount;
        totalInstructions += instructionCount;
    }
}
