package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.List;

import javassist.CannotCompileException;
import javassist.CtBehavior;

public class MethodCalls extends AbstractJavassistTool {

    private static long nmethods = 0;

    public MethodCalls(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    public static void incBehavior(String name) {
        nmethods++;
    }

    public static void printStatistics() {
        System.out.println(String.format("[%s] Number of executed methods: %s", MethodCalls.class.getSimpleName(), nmethods));
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);
        behavior.insertAfter(String.format("%s.incBehavior(\"%s\");", MethodCalls.class.getName(), behavior.getLongName()));

        if (behavior.getName().equals("main")) {
            behavior.insertAfter(String.format("%s.printStatistics();", MethodCalls.class.getName()));
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);
    }
}
