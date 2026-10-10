package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.List;

import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.Opcode;

public class WeightedInstructions extends AbstractJavassistTool {

    private static final int TYPE_STACK_LOCAL    = 0;
    private static final int TYPE_ADD_SUB_NEG    = 1;
    private static final int TYPE_MUL            = 2;
    private static final int TYPE_DIV_REM        = 3;
    private static final int TYPE_BITWISE_SHIFT  = 4;
    private static final int TYPE_COMPARE_BRANCH = 5;
    private static final int TYPE_FIELD_RW       = 6;
    private static final int TYPE_ARRAY_RW       = 7;
    private static final int TYPE_ALLOCATION     = 8;
    private static final int TYPE_INVOCATION     = 9;
    private static final int TYPE_CAST_CONV      = 10;
    private static final int TYPE_SYNC_EXCEPTION = 11;
    private static final int TYPE_RETURN         = 12;

    private static final int[] TYPE_WEIGHTS = {
        1,  // stack/local
        1,  // add/sub/neg
        2,  // multiply
        4,  // divide/remainder
        1,  // bitwise/shift
        1,  // compare/branch
        3,  // field read/write
        3,  // array read/write
        8,  // allocation
        6,  // method invocation
        2,  // cast/conversion
        10, // sync/exception
        1   // return
    };

    // Per-thread counters (one value per thread, no contention at all)
    private static final ThreadLocal<long[]> threadNweighted = ThreadLocal.withInitial(() -> new long[1]);

    public WeightedInstructions(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    // -------------------------------------------------------------------------
    // Runtime methods — called by injected bytecode, must be public static
    // -------------------------------------------------------------------------

    public static void incBasicBlock(int length, int weightedCost) {
        // Per-thread updates — ThreadLocal guarantees no sharing between threads
        threadNweighted.get()[0] += weightedCost;
    }

    public static void printStatistics() {
        System.out.println(String.format("[%s] Total weighted instructions: %s",
            WeightedInstructions.class.getSimpleName(), threadNweighted.get()[0]));
    }

    // Public accessors for external metric collection (e.g. load balancer)
    public static long getThreadWeighted()    { return threadNweighted.get()[0]; }

    // -------------------------------------------------------------------------
    // run at instrumentation time
    // -------------------------------------------------------------------------

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        CodeAttribute codeAttribute = behavior.getMethodInfo().getCodeAttribute();
        if (!behavior.getName().equals("main")
                && codeAttribute != null
                && codeAttribute.getExceptionTable() != null
                && codeAttribute.getExceptionTable().size() > 0) {
            return;
        }

        super.transform(behavior);
        if (behavior.getName().equals("main")) {
            behavior.insertAfter(String.format("%s.printStatistics();",
                    WeightedInstructions.class.getName()));
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);

        // Compute the weighted cost by walking the bytecode once — instrumentation time only.
        int weightedCost = computeWeightedCost(block.getBehavior(),
                block.getPosition(), block.getLength());

        // Bake both values as integer literals into the injected call.
        // At runtime this becomes e.g.: WeightedInstructions.incBasicBlock(4, 11);
        try {
            block.getBehavior().insertAt(block.getLine(), String.format(
                    "%s.incBasicBlock(%s, %s);",
                    WeightedInstructions.class.getName(),
                    block.getLength(),
                    weightedCost));
        } catch (CannotCompileException e) {
            // Block could not be instrumented — skip it silently.
        }
    }

    // -------------------------------------------------------------------------
    // Instrumentation-time helpers
    // -------------------------------------------------------------------------

    /**
     * Walks bytecode in [startPos, startPos+length) and sums weights.
     * Runs once per basic block during class loading, not during execution.
     */
    private static int computeWeightedCost(CtBehavior behavior, int startPos, int length)
            throws CannotCompileException {
        CodeAttribute ca = behavior.getMethodInfo().getCodeAttribute();
        if (ca == null) return 0;

        int cost = 0;
        int endPos = startPos + length;
        CodeIterator it = ca.iterator();

        try {
            while (it.hasNext()) {
                int pos = it.next();
                if (pos >= startPos && pos < endPos) {
                    cost += TYPE_WEIGHTS[classifyOpcode(it.byteAt(pos))];
                }
            }
        } catch (Exception e) {
            throw new CannotCompileException(e);
        }

        return cost;
    }

    private static int classifyOpcode(int opcode) {
        switch (opcode) {
            case Opcode.IADD: case Opcode.LADD: case Opcode.FADD: case Opcode.DADD:
            case Opcode.ISUB: case Opcode.LSUB: case Opcode.FSUB: case Opcode.DSUB:
            case Opcode.INEG: case Opcode.LNEG: case Opcode.FNEG: case Opcode.DNEG:
                return TYPE_ADD_SUB_NEG;

            case Opcode.IMUL: case Opcode.LMUL: case Opcode.FMUL: case Opcode.DMUL:
                return TYPE_MUL;

            case Opcode.IDIV: case Opcode.LDIV: case Opcode.FDIV: case Opcode.DDIV:
            case Opcode.IREM: case Opcode.LREM: case Opcode.FREM: case Opcode.DREM:
                return TYPE_DIV_REM;

            case Opcode.IAND: case Opcode.LAND: case Opcode.IOR:  case Opcode.LOR:
            case Opcode.IXOR: case Opcode.LXOR:
            case Opcode.ISHL: case Opcode.LSHL: case Opcode.ISHR: case Opcode.LSHR:
            case Opcode.IUSHR: case Opcode.LUSHR:
                return TYPE_BITWISE_SHIFT;

            case Opcode.LCMP:  case Opcode.FCMPL: case Opcode.FCMPG:
            case Opcode.DCMPL: case Opcode.DCMPG:
            case Opcode.IFEQ:  case Opcode.IFNE:  case Opcode.IFLT:
            case Opcode.IFGE:  case Opcode.IFGT:  case Opcode.IFLE:
            case Opcode.IF_ICMPEQ: case Opcode.IF_ICMPNE: case Opcode.IF_ICMPLT:
            case Opcode.IF_ICMPGE: case Opcode.IF_ICMPGT: case Opcode.IF_ICMPLE:
            case Opcode.IF_ACMPEQ: case Opcode.IF_ACMPNE:
            case Opcode.GOTO:  case Opcode.GOTO_W:
            case Opcode.JSR:   case Opcode.JSR_W: case Opcode.RET:
            case Opcode.TABLESWITCH: case Opcode.LOOKUPSWITCH:
            case Opcode.IFNULL: case Opcode.IFNONNULL:
                return TYPE_COMPARE_BRANCH;

            case Opcode.GETFIELD: case Opcode.GETSTATIC:
            case Opcode.PUTFIELD: case Opcode.PUTSTATIC:
                return TYPE_FIELD_RW;

            case Opcode.IALOAD: case Opcode.LALOAD: case Opcode.FALOAD:
            case Opcode.DALOAD: case Opcode.AALOAD: case Opcode.BALOAD:
            case Opcode.CALOAD: case Opcode.SALOAD:
            case Opcode.IASTORE: case Opcode.LASTORE: case Opcode.FASTORE:
            case Opcode.DASTORE: case Opcode.AASTORE: case Opcode.BASTORE:
            case Opcode.CASTORE: case Opcode.SASTORE:
            case Opcode.ARRAYLENGTH:
                return TYPE_ARRAY_RW;

            case Opcode.NEW: case Opcode.NEWARRAY:
            case Opcode.ANEWARRAY: case Opcode.MULTIANEWARRAY:
                return TYPE_ALLOCATION;

            case Opcode.INVOKEVIRTUAL: case Opcode.INVOKESPECIAL:
            case Opcode.INVOKESTATIC:  case Opcode.INVOKEINTERFACE:
            case Opcode.INVOKEDYNAMIC:
                return TYPE_INVOCATION;

            case Opcode.CHECKCAST: case Opcode.INSTANCEOF:
            case Opcode.I2L: case Opcode.I2F: case Opcode.I2D:
            case Opcode.L2I: case Opcode.L2F: case Opcode.L2D:
            case Opcode.F2I: case Opcode.F2L: case Opcode.F2D:
            case Opcode.D2I: case Opcode.D2L: case Opcode.D2F:
            case Opcode.I2B: case Opcode.I2C: case Opcode.I2S:
                return TYPE_CAST_CONV;

            case Opcode.MONITORENTER: case Opcode.MONITOREXIT: case Opcode.ATHROW:
                return TYPE_SYNC_EXCEPTION;

            case Opcode.IRETURN: case Opcode.LRETURN: case Opcode.FRETURN:
            case Opcode.DRETURN: case Opcode.ARETURN: case Opcode.RETURN:
                return TYPE_RETURN;

            default:
                return TYPE_STACK_LOCAL;
        }
    }
}
