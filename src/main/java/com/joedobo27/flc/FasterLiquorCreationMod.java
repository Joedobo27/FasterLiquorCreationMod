package com.joedobo27.flc;


import com.wurmonline.server.items.*;
import javassist.*;
import javassist.bytecode.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.CodeReplacer;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.stream.IntStream;


public class FasterLiquorCreationMod implements WurmServerMod, PreInitable, Configurable, ServerStartedListener{

    private static long durationWurmSeconds = 2419200L;
    private static int stillProcessesGrams = 1000;
    private static Logger logger = Logger.getLogger(FasterLiquorCreationMod.class.getName());


    @Override
    public void configure(Properties properties) {
        durationWurmSeconds = Long.parseLong(properties.getProperty("durationWurmSeconds", Long.toString(durationWurmSeconds)));
        stillProcessesGrams = Integer.parseInt(properties.getProperty("stillProcessesGrams", Integer.toString(stillProcessesGrams)));
    }

    @Override
    public void preInit() {
        pollFermentingBytecode();
        pollDistillingBytecode();
        pollBytecode();
    }

    @Override
    public void onServerStarted() {
        ItemTemplate[] templates1 = ItemTemplateFactory.getInstance().getTemplates();

        Arrays.stream(templates1)
                .filter(this::isPositiveDecay)
                .forEach(this::reduceDecayTime);
    }

    private boolean isPositiveDecay(ItemTemplate itemTemplate){
        try {
            return (boolean)ReflectionUtil.getPrivateField(itemTemplate,ReflectionUtil.getField(
                        Class.forName("com.wurmonline.server.items.ItemTemplate"), "positiveDecay"));
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            logger.warning(e.getMessage());
            return false;
        }
    }

    private void reduceDecayTime(ItemTemplate itemTemplate) {
        try{
            ReflectionUtil.setPrivateField(itemTemplate,
                    ReflectionUtil.getField(Class.forName("com.wurmonline.server.items.ItemTemplate"), "decayTime"),
                    1L);
        }catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e){
            logger.warning(e.getMessage());
        }
    }

    private void pollDistillingBytecode() {
        try {
            //if (undistilled.lastMaintained > WurmCalendar.currentTime - 600L) {
            //    return;

            CtClass ctClassItem = HookManager.getInstance().getClassPool().getCtClass("com.wurmonline.server.items.Item");
            ctClassItem.getClassFile().compact();
            Bytecode find = new Bytecode(ctClassItem.getClassFile().getConstPool());
            find.addGetfield(ctClassItem, "lastMaintained", "J");
            find.addGetstatic(HookManager.getInstance().getClassPool().get("com.wurmonline.server.WurmCalendar"),
                    "currentTime", "J");
            find.addLdc2w(600L);

            Bytecode replace = new Bytecode(ctClassItem.getClassFile().getConstPool());
            replace.addGetfield(ctClassItem, "lastMaintained", "J");
            replace.addGetstatic(HookManager.getInstance().getClassPool().get("com.wurmonline.server.WurmCalendar"),
                    "currentTime", "J");
            replace.addLdc2w(durationWurmSeconds);

            CodeReplacer codeReplacer = new CodeReplacer(ctClassItem.getDeclaredMethod("pollDistilling")
                    .getMethodInfo().getCodeAttribute());
            codeReplacer.replaceCode(find.get(), replace.get());

            boolean[] successes = new boolean[1];
            Arrays.fill(successes, false);
            final boolean[] setLastMaintained = {false};
            ctClassItem.getDeclaredMethod("pollDistilling").instrument(new ExprEditor() {
                @Override public void edit(MethodCall methodCall) throws CannotCompileException {
                    if (Objects.equals(methodCall.getMethodName(), "setLastMaintained")
                            && !setLastMaintained[0]){
                        setLastMaintained[0] = true;
                    }
                    else if(Objects.equals(methodCall.getMethodName(), "min") && setLastMaintained[0]) {
                        setLastMaintained[0] = false;
                        methodCall.replace("$1 = "+ stillProcessesGrams +"; $_ = $proceed($$);");
                        successes[0] = true;
                    }
                }
            });
            for (boolean b:successes){
                if (!b)
                    throw new RuntimeException("pollDistilling method instrument error. " + Arrays.toString(successes));
            }

        } catch (NotFoundException | BadBytecode | CannotCompileException e){
            logger.warning(e.getMessage() + "pollDistilling not changed.");
        }
    }

    private void pollFermentingBytecode() {
        try {
            CtClass itemClass = HookManager.getInstance().getClassPool().getCtClass("com.wurmonline.server.items.Item");

            ConstPool constPool = itemClass.getClassFile().getConstPool();
            Bytecode find = new Bytecode(constPool);
            codeBranching(find, Opcode.IFEQ, 9);
            find.addLdc2w(86400L);
            codeBranching(find, Opcode.GOTO, 6);
            find.addLdc2w(2419200L);

            Bytecode replace = new Bytecode(constPool);
            codeBranching(replace, Opcode.IFEQ, 9);
            replace.addLdc2w(durationWurmSeconds);
            codeBranching(replace, Opcode.GOTO, 6);
            replace.addLdc2w(durationWurmSeconds);

            CodeReplacer codeReplacer = new CodeReplacer(itemClass.getDeclaredMethod("pollFermenting").getMethodInfo().getCodeAttribute());

            codeReplacer.replaceCode(find.get(), replace.get());
            logger.fine("Barrel fermenting accelerated from 2419200l to " + Long.toString(durationWurmSeconds) + " wurm time.");
            //pollFermentingMethod.getMethodInfo().rebuildStackMapIf6(HookManager.getInstance().getClassPool(), itemClass.getClassFile());
        } catch (NotFoundException | BadBytecode e) {
            logger.warning(e.getMessage() + " pollFermenting not changed.");
        }
    }

    private void pollBytecode() {
        try {
            CtClass ctClassItem = HookManager.getInstance().getClassPool().getCtClass("com.wurmonline.server.items.Item");
            // remove dead end duplicate class references in the constant pool.
            ctClassItem.getClassFile().compact();

            ////////////////////////////////////////
            //if (this.getTemplateId() == 1178 && Server.rand.nextInt(20) == 0) {
            //    this.pollDistilling();
            //}
            int distillingTableLineNumber = getTableLineNumber(getTableLineNumberForMethod(ctClassItem, Opcode.INVOKEVIRTUAL,
                    "pollDistilling", Descriptor.ofMethod(CtPrimitiveType.voidType, null),
                    "poll", Descriptor.ofMethod(CtPrimitiveType.booleanType, new CtClass[]{
                            CtPrimitiveType.booleanType, CtPrimitiveType.booleanType, CtPrimitiveType.longType})), -1,
                    ctClassItem.getMethod("poll", Descriptor.ofMethod(CtPrimitiveType.booleanType,
                            new CtClass[]{CtPrimitiveType.booleanType, CtPrimitiveType.booleanType, CtPrimitiveType.longType}))
                            .getMethodInfo());

            //////////////////////
            //if (Server.rand.nextInt(20) == 0) {
            //    this.pollFermenting();
            //}
            int fermenting1TableLineNumber = getTableLineNumber(getTableLineNumberForMethod(ctClassItem, Opcode.INVOKEVIRTUAL,
                    "pollFermenting", Descriptor.ofMethod(CtPrimitiveType.voidType, null),
                    "poll", Descriptor.ofMethod(CtPrimitiveType.booleanType, new CtClass[]{
                            CtPrimitiveType.booleanType, CtPrimitiveType.booleanType, CtPrimitiveType.longType})), -1,
                    ctClassItem.getMethod("poll", Descriptor.ofMethod(CtPrimitiveType.booleanType,
                            new CtClass[]{CtPrimitiveType.booleanType, CtPrimitiveType.booleanType, CtPrimitiveType.longType}))
                            .getMethodInfo());

            //////////////////////
            //if (Server.rand.nextInt(20) == 0) {
            //    this.pollFermenting();
            //}
            int fermenting2TableLineNumber = getTableLineNumber(getTableLineNumberForMethod(ctClassItem, Opcode.INVOKEVIRTUAL,
                    "pollFermenting", Descriptor.ofMethod(CtPrimitiveType.voidType, null),
                    "poll", Descriptor.ofMethod(CtPrimitiveType.booleanType, new CtClass[]{ctClassItem,
                            CtPrimitiveType.intType, CtPrimitiveType.booleanType, CtPrimitiveType.booleanType,
                            CtPrimitiveType.booleanType, CtPrimitiveType.booleanType, CtPrimitiveType.booleanType})), -1,
                    ctClassItem.getDeclaredMethod("poll",
                            new CtClass[]{ctClassItem, CtPrimitiveType.intType, CtPrimitiveType.booleanType, CtPrimitiveType.booleanType,
                                    CtPrimitiveType.booleanType, CtPrimitiveType.booleanType, CtPrimitiveType.booleanType})
                            .getMethodInfo());

            boolean[] poll1successes = new boolean[2];
            Arrays.fill(poll1successes, false);
            ctClassItem.getMethod("poll", Descriptor.ofMethod(CtPrimitiveType.booleanType,
                    new CtClass[]{CtPrimitiveType.booleanType, CtPrimitiveType.booleanType, CtPrimitiveType.longType}))
                    .instrument(new ExprEditor() {
                        @Override public void edit(MethodCall methodCall) throws CannotCompileException {
                            if (Objects.equals(methodCall.getMethodName(), "nextInt") &&
                                    methodCall.getLineNumber() == distillingTableLineNumber) {
                                methodCall.replace("$1 = 1; $_ = $proceed($$);");
                                poll1successes[0] = true;
                            } else if (Objects.equals(methodCall.getMethodName(), "nextInt") &&
                                    methodCall.getLineNumber() == fermenting1TableLineNumber) {
                                methodCall.replace("$1 = 1; $_ = $proceed($$);");
                                poll1successes[1] = true;
                            }
                        }
                    });
            for (boolean b:poll1successes){
                if (!b)
                    throw new RuntimeException("poll() method instrument error. " + Arrays.toString(poll1successes));
            }

            boolean[] poll2Successes = new boolean[1];
            Arrays.fill(poll2Successes, false);
            ctClassItem.getDeclaredMethod("poll", new CtClass[]{ctClassItem, CtPrimitiveType.intType,
                    CtPrimitiveType.booleanType, CtPrimitiveType.booleanType, CtPrimitiveType.booleanType,
                    CtPrimitiveType.booleanType, CtPrimitiveType.booleanType}).instrument(new ExprEditor() {
                        @Override public void edit(MethodCall methodCall) throws CannotCompileException {
                            if (Objects.equals(methodCall.getMethodName(), "nextInt") &&
                                    methodCall.getLineNumber() == fermenting2TableLineNumber) {
                                methodCall.replace("$1 = 1; $_ = $proceed($$);");
                                poll2Successes[0] = true;
                            }
                        }
            });
            for (boolean b:poll2Successes){
                if (!b)
                    throw new RuntimeException("poll() method instrument error. " + Arrays.toString(poll2Successes));
            }

        }catch (NotFoundException | BadBytecode | CannotCompileException | RuntimeException e) {
            logger.warning(e.getMessage());
        }
    }

    private static void codeBranching(Bytecode bytecode, int opcode, int branchCount){
        bytecode.addOpcode(opcode);
        bytecode.add((branchCount >>> 8) & 0xFF, branchCount & 0xFF);
    }

    /**
     * https://en.wikipedia.org/wiki/Java_bytecode_instruction_listings
     * @param size how many bytes is the instruction.
     * @param index where is the instruction at.
     * @param codeIterator CodeIterator object that has all the bytecode.
     * @return An encoded byte[] into long. [opcode][byte 1][byte 2]...[byte n]
     */
    private static long getBytecodeAtIndex(int size, int index, CodeIterator codeIterator) {
        int[] ints = new int[size];
        switch (size) {
            case 1:
                // Only a Opcode.
                ints = IntStream.range(0, size)
                        .map(value -> codeIterator.byteAt(index + value))
                        .toArray();
                break;
            case 2:
                // An Opcode + 1 byte of additional information
                ints = IntStream.range(0, size)
                        .map(value -> codeIterator.byteAt(index + value))
                        .toArray();
                break;
            case 3:
                // many of these: Opcode + 2 bytes of additional information
                ints = IntStream.range(0, size)
                        .map(value -> codeIterator.byteAt(index + value))
                        .toArray();
                break;
            case 4:
                // few of these: multianewarray or in some cases wide.
                ints = IntStream.range(0, size)
                        .map(value -> codeIterator.byteAt(index + value))
                        .toArray();
                break;
            case 5:
                // onl invokeinterface, invokedynamic, goto_w, jsr_w
                ints = IntStream.range(0, size)
                        .map(value -> codeIterator.byteAt(index + value))
                        .toArray();
                break;
            case 6:
                // only a wide in some cases.
                ints = IntStream.range(0, size)
                        .map(value -> codeIterator.byteAt(index + value))
                        .toArray();
                break;
        }
        byte[] bytes = new byte[ints.length];
        for(int i=0;i<ints.length;i++) {
            bytes[i] = (byte)ints[i];
        }
        return byteArrayToLong(bytes);
        // there is tableswitch at 16+ size and lookupswitch at 8+ size. these likely need special treatment.
    }

    private static long byteArrayToLong(byte[] bytesOriginal) {
        if (bytesOriginal.length < 8) {
            byte[] bytesLongPadded = new byte[8];
            System.arraycopy(bytesOriginal, 0, bytesLongPadded, 8 - bytesOriginal.length, bytesOriginal.length);
            return ByteBuffer.wrap(bytesLongPadded).getLong();
        }
        else
            return ByteBuffer.wrap(bytesOriginal).getLong();
    }

    @SuppressWarnings("SameParameterValue")
    private static int getTableLineNumberForMethod(CtClass ctClass, int opcode, String targetName, String targetDescriptor,
                                                   String parentName, String parentDescriptor) throws BadBytecode, RuntimeException {
        // Get the bytecode for the method call in question.
        Bytecode bytecode = new Bytecode(ctClass.getClassFile().getConstPool());
        if (opcode == Opcode.INVOKEVIRTUAL)
            bytecode.addInvokevirtual(ctClass, targetName, targetDescriptor);
        else if (opcode == Opcode.INVOKESTATIC)
            bytecode.addInvokestatic(ctClass, targetName, targetDescriptor);
        long findBytecode = byteArrayToLong(bytecode.get());

        List methods = ctClass.getClassFile().getMethods();
        MethodInfo methodInfo = IntStream.range(0, methods.size())
                .mapToObj(value -> (MethodInfo) methods.get(value))
                .filter(methodInfo1 -> Objects.equals(parentName, methodInfo1.getName()))
                .filter(methodInfo1 -> Objects.equals(parentDescriptor, methodInfo1.getDescriptor()))
                .findFirst()
                .orElseThrow(NullPointerException::new);
        // Get the bytecode index of the parent method where the method in question is called.
        int bytecodeIndex = 0;
        CodeIterator codeIterator = methodInfo.getCodeAttribute().iterator();
        codeIterator.begin();
        while (codeIterator.hasNext()) {
            int index = codeIterator.next();
            long foundBytecode = getBytecodeAtIndex(codeIterator.lookAhead() - index, index, codeIterator);
            if (foundBytecode == findBytecode) {
                bytecodeIndex = index;
            }
        }

        // Get the line number table entry for pollDistilling method call. The call to nextInt method will be
        // in this entry and the number can be used with expression editor.
        LineNumberAttribute lineNumberAttribute = (LineNumberAttribute) methodInfo.getCodeAttribute()
                .getAttribute(LineNumberAttribute.tag);
        int lineNumber = lineNumberAttribute.toLineNumber(bytecodeIndex);
        int lineNumberTableOrdinal =  IntStream.range(0, lineNumberAttribute.tableLength())
                .filter(value -> Objects.equals(lineNumberAttribute.lineNumber(value), lineNumber))
                .findFirst()
                .orElseThrow(RuntimeException::new);
        return lineNumberAttribute.lineNumber(lineNumberTableOrdinal);
    }

    private static int getTableLineNumber(int lineNumber, int offset, MethodInfo methodInfo) throws RuntimeException {
        LineNumberAttribute lineNumberAttribute = (LineNumberAttribute) methodInfo.getCodeAttribute()
                .getAttribute(LineNumberAttribute.tag);
        int lineNumberTableOrdinal = IntStream.range(0, lineNumberAttribute.tableLength())
                .filter(value -> Objects.equals(lineNumberAttribute.lineNumber(value), lineNumber))
                .findFirst()
                .orElseThrow(RuntimeException::new);
        if (lineNumberTableOrdinal + offset < 0 || lineNumberTableOrdinal + offset > lineNumberAttribute.tableLength() - 1)
            throw new RuntimeException("offset makes lineNumberTableOrdinal out of bounds.");
        return lineNumberAttribute.lineNumber(lineNumberTableOrdinal + offset);
    }
}
