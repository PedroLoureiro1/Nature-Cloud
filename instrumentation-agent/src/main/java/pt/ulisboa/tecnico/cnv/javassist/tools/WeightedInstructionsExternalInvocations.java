package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.List;

import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

/**
 * Extends WeightedInstructions with runtime-measured String-op costs for DNA classes.
 *
 * WeightedInstructions bakes static opcode weights at instrumentation time.
 * This subclass additionally injects ExprEditor hooks into DNA classes so that
 * String.substring / String.equals costs scale with the number of characters
 * actually processed at runtime — not a fixed invocation weight.
 *
 * Use this tool on the worker for accurate DNA complexity measurement.
 * HhsMetricsRuntime reads WeightedInstructions.getThreadWeighted(), which is
 * correct here because both incBasicBlock and incStringOp write to the same
 * threadNweighted counter declared in the superclass.
 */
public class WeightedInstructionsExternalInvocations extends WeightedInstructions {

    public WeightedInstructionsExternalInvocations(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);
        if (behavior.getDeclaringClass().getName().startsWith("pt.ulisboa.tecnico.cnv.dna.")) {
            injectStringsComplexity(behavior);
        }
    }

    /**
     * Replaces each String.substring(int,int) and String.equals() call in DNA
     * classes with a wrapper that also calls WeightedInstructions.incStringOp(),
     * adding cost proportional to the number of characters actually processed.
     *
     * Runs AFTER the superclass injects static basic-block weights, so the two
     * contributions are simply additive.
     */
    private void injectStringsComplexity(CtBehavior behavior) {
        try {
            behavior.instrument(new ExprEditor() {
                @Override
                public void edit(MethodCall m) throws CannotCompileException {
                    if (!"java.lang.String".equals(m.getClassName())) return;
                    switch (m.getMethodName()) {
                        case "substring":
                            if ("(II)Ljava/lang/String;".equals(m.getSignature())) {
                                // $1=beginIndex, $2=endIndex; cost = chars copied = endIndex-beginIndex
                                m.replace("{ $_ = $proceed($$); "
                                    + WeightedInstructions.class.getName()
                                    + ".incStringOp($2 - $1); }");
                            }
                            break;
                        case "equals":
                            // $0=receiver String; cost = chars compared = receiver.length()
                            m.replace("{ $_ = $proceed($$); "
                                + WeightedInstructions.class.getName()
                                + ".incStringOp(((String)$0).length()); }");
                            break;
                        default:
                            break;
                    }
                }
            });
        } catch (Exception e) {
            // Falls back to static weights
        }
    }
}
