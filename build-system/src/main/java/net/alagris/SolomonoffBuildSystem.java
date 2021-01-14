package net.alagris;

import net.alagris.TomlParser.*;

import net.automatalib.automata.transducers.MealyMachine;
import net.automatalib.words.Alphabet;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.jgrapht.traverse.TopologicalOrderIterator;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.Queue;
import java.util.concurrent.*;

public class SolomonoffBuildSystem {

    static abstract class VarDef<G> {
        final String id;
        final String hashedName;
        final Path cacheFilePath;
        ArrayList<Pair<SolomonoffWeighted, SolomonoffWeighted>> funcTypes = new ArrayList<>();
        ArrayList<Pair<SolomonoffWeighted, SolomonoffWeighted>> productTypes = new ArrayList<>();
        boolean needsRecompilation;

        protected VarDef(String id, String sourceFile, String cacheLocation) throws IOException {
            this.id = id;
            this.hashedName = Integer.toHexString(id.hashCode());
            this.cacheFilePath = Paths.get(cacheLocation, hashedName);
            final Path sourceFilePath = Paths.get(sourceFile);
            final FileTime sourceFileModificationTime =
                    (FileTime) Files.getAttribute(sourceFilePath, "lastModifiedTime");
            final boolean needsRecompilation;
            if (Files.exists(cacheFilePath)) {
                final FileTime cacheModificationTime =
                        (FileTime) Files.getAttribute(cacheFilePath, "lastModifiedTime");
                final int diff = cacheModificationTime.compareTo(sourceFileModificationTime);
                needsRecompilation = diff < 0;
            } else {
                needsRecompilation = true;
            }
            this.needsRecompilation = needsRecompilation;
        }
    }

    static class VarDefInfer<G> extends VarDef<G> {

        VarDefInfer(String id, String sourceFile, String cacheLocation) throws IOException {
            super(id, sourceFile, cacheLocation);
        }
    }

    static class VarDefAST<G> extends VarDef<G> {

        final SolomonoffWeighted def;

        VarDefAST(String id, SolomonoffWeighted def, String sourceFile, String cacheLocation) throws IOException {
            super(id, sourceFile, cacheLocation);
            this.def = def;

        }
    }

    private static ArrayList<Pair<IntSeq, IntSeq>> loadStringFile(Path file) throws IOException {
        final ArrayList<Pair<IntSeq, IntSeq>> informant = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file.toFile())))) {

            String line;
            while ((line = in.readLine()) != null) {
                final int tab = line.indexOf('\t');
                if (tab == -1) {
                    informant.add(Pair.of(new IntSeq(line), null));
                } else {
                    informant.add(Pair.of(new IntSeq(line.subSequence(0, tab)),
                            new IntSeq(line.subSequence(tab + 1, line.length()))));
                }
            }
        }
        return informant;
    }


    public static <N, G extends IntermediateGraph<Pos, LexUnicodeSpecification.E, LexUnicodeSpecification.P, N>> void
    runCompiler(Config config, OptimisedLexTransducer<N, G> specs)
            throws ExecutionException, InterruptedException, CompilationError, IOException {

        final ExecutorService pool = Executors.newWorkStealingPool();
        final Queue<Future<Void>> queue = new LinkedList<>();

        final SolomonoffWeightedParser.ConcurrentCollector collector =
                new SolomonoffWeightedParser.ConcurrentCollector();

        final ConcurrentHashMap<String, Future<G>> compiled = new ConcurrentHashMap<>();
        final HashMap<String, VarDef<G>> definitions = new HashMap<>();
        // parse user's mealy files
        for (final Source sourceFile : config.source) {
            final Path path = Paths.get(sourceFile.path);
            final String extension = FilenameUtils.getExtension(sourceFile.path);
            final String name = FilenameUtils.removeExtension(sourceFile.path);

            switch (extension) {
                case "mealy": {
                    System.out.println("Reading " + sourceFile.path);
                    queue.add(pool.submit(() -> {
                        final File mealyFile = new File(sourceFile.path);
                        if (!mealyFile.exists()) {
                            throw new CLIException.MealyFileException(mealyFile.getPath());
                        }
                        final SolomonoffGrammarLexer lexer =
                                new SolomonoffGrammarLexer(CharStreams.fromPath(mealyFile.toPath()));
                        final SolomonoffGrammarParser parser =
                                new SolomonoffGrammarParser(new CommonTokenStream(lexer));

                        parser.addErrorListener(new BaseErrorListener() {
                            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                                    int charPositionInLine, String msg, RecognitionException e) {
                                System.err.println("line " + line + ":" + charPositionInLine + " " + msg + " " + e);
                            }
                        });

                        final SolomonoffWeightedParser listener =
                                new SolomonoffWeightedParser(collector, sourceFile.path,specs);
                        ParseTreeWalker.DEFAULT.walk(listener, parser.start());
                        assert listener.stack.isEmpty();
                        return null;
                    }));
                    break;
                }
                case "ostia": {
                    final VarDefInfer<G> def = new VarDefInfer<G>(name, sourceFile.path, config.cacheLocation);
                    definitions.put(name, def);
                    compiled.put(name, pool.submit(() -> {
                        if (def.needsRecompilation) {
                            System.out.println("Inferring " + sourceFile.path);
                            try {
                                final HashMap<Integer, Integer> symbolToIndex = new HashMap<>();
                                final ArrayList<Pair<IntSeq, IntSeq>> informant = loadStringFile(path);
                                LearnLibCompatibility.inferAlphabet(informant.iterator(), symbolToIndex);
                                final int[] indexToSymbol = new int[symbolToIndex.size()];
                                for (Map.Entry<Integer, Integer> e : symbolToIndex.entrySet()) {
                                    indexToSymbol[e.getValue()] = e.getKey();
                                }
                                final Iterator<Pair<IntSeq, IntSeq>> mapped =
                                        LearnLibCompatibility.mapSymbolsToIndices(informant.iterator(), symbolToIndex);
                                final OSTIA.State ptt = OSTIA.buildPtt(symbolToIndex.size(), mapped);
                                OSTIA.ostia(ptt);
                                final G g = specs.specs.compileIntermediateOSTIA(ptt,
                                        i -> indexToSymbol[i], x -> Pos.NONE);
                                try (FileOutputStream f = new FileOutputStream(def.cacheFilePath.toFile())) {
                                    specs.specs.compressBinary(g, new DataOutputStream(f));
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    def.cacheFilePath.toFile().deleteOnExit();
                                }
                                return g;
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            System.out.println("Loaded from cache " + sourceFile.path);
                            try (DataInputStream dis =
                                         new DataInputStream(new FileInputStream(def.cacheFilePath.toFile()))) {
                                return specs.specs.decompressBinary(Pos.NONE, dis);
                            }
                        }
                    }));
                    break;
                }
                case "rpni": {
                    final VarDefInfer<G> def = new VarDefInfer<G>(name, sourceFile.path, config.cacheLocation);
                    definitions.put(name, def);
                    compiled.put(name, pool.submit(() -> {
                        if (def.needsRecompilation) {
                            System.out.println("Inferring " + sourceFile.path);
                            try {
                                final G g = OptimisedLexTransducer.dfaToIntermediate(specs.specs,
                                        Pos.NONE, LearnLibCompatibility.rpni(loadStringFile(path)));
                                try (FileOutputStream f = new FileOutputStream(def.cacheFilePath.toFile())) {
                                    specs.specs.compressBinary(g, new DataOutputStream(f));
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    def.cacheFilePath.toFile().deleteOnExit();
                                }
                                return g;
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            System.out.println("Loaded from cache " + sourceFile.path);
                            try (DataInputStream dis =
                                         new DataInputStream(new FileInputStream(def.cacheFilePath.toFile()))) {
                                return specs.specs.decompressBinary(Pos.NONE, dis);
                            }
                        }
                    }));
                    break;
                }
                case "rpni_mealy": {
                    final VarDefInfer<G> def = new VarDefInfer<G>(name, sourceFile.path, config.cacheLocation);
                    definitions.put(name, def);
                    compiled.put(name, pool.submit(() -> {
                        if (def.needsRecompilation) {
                            System.out.println("Inferring " + sourceFile.path);
                            try {
                                Pair<Alphabet<Integer>, MealyMachine<?, Integer, ?, Integer>> alphAndMealy =
                                        LearnLibCompatibility.rpniMealy(loadStringFile(path));
                                G g = LearnLibCompatibility.mealyToIntermediate(specs.specs, alphAndMealy.l(),
                                        alphAndMealy.r(), s -> Pos.NONE,
                                        (in, out) -> specs.specs.createFullEdgeOverSymbol(in,
                                                specs.specs.createPartialEdge(new IntSeq(out), 0)),
                                        s -> new LexUnicodeSpecification.P(IntSeq.Epsilon, 0));
                                try (FileOutputStream f = new FileOutputStream(def.cacheFilePath.toFile())) {
                                    specs.specs.compressBinary(g, new DataOutputStream(f));
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    def.cacheFilePath.toFile().deleteOnExit();
                                }
                                return g;
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            System.out.println("Loaded from cache " + sourceFile.path);
                            try (DataInputStream dis =
                                         new DataInputStream(new FileInputStream(def.cacheFilePath.toFile()))) {
                                return specs.specs.decompressBinary(Pos.NONE, dis);
                            }
                        }
                    }));
                    break;
                }
                case "rpni_edsm": {
                    final VarDefInfer<G> def = new VarDefInfer<G>(name, sourceFile.path, config.cacheLocation);
                    definitions.put(name, def);
                    compiled.put(name, pool.submit(() -> {
                        if (def.needsRecompilation) {
                            System.out.println("Inferring " + sourceFile.path);
                            try {
                                final G g = OptimisedLexTransducer.dfaToIntermediate(specs.specs, Pos.NONE,
                                        LearnLibCompatibility.rpniEDSM(loadStringFile(path)));
                                try (FileOutputStream f = new FileOutputStream(def.cacheFilePath.toFile())) {
                                    specs.specs.compressBinary(g, new DataOutputStream(f));
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    def.cacheFilePath.toFile().deleteOnExit();
                                }
                                return g;
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            System.out.println("Loaded from cache " + sourceFile.path);
                            try (DataInputStream dis =
                                         new DataInputStream(new FileInputStream(def.cacheFilePath.toFile()))) {
                                return specs.specs.decompressBinary(Pos.NONE, dis);
                            }
                        }
                    }));
                    break;
                }
                default:
                    System.err.println("Unrecognised file extension. Ignoring " + sourceFile.path);
                    break;
            }
        }

        //wait for termination of all tasks
        for (Future<Void> task : queue) {
            task.get();
        }
        queue.clear();


        // initialize dependency graph
        final DirectedAcyclicGraph<String, Object> dependencyOf =
                new DirectedAcyclicGraph<>(null, null, false);
        for (String builtInVariable : specs.specs.variableAssignments.keySet()) {
            dependencyOf.addVertex(builtInVariable);
        }

        // collect all definitions into a single hashmap together with their respective type judgements
        for (Map.Entry<String, SolomonoffWeighted> def : collector.definitions.entrySet()) {
            final String id = def.getKey();
            dependencyOf.addVertex(id);
            final String sourceFile = collector.sourceFiles.get(id);
            definitions.put(id, new VarDefAST<G>(id, def.getValue(), sourceFile, config.cacheLocation));
        }


        //Build reversed dependency graph. A directed edge (X,Y) will represent that
        //X is a dependency of Y. Equivalently Y depends on X.
        // This will be necessary to later build topological order in which
        // X comes before Y whenever (X,Y) is an edge.
        for (Pair<String, String> dependency : collector.dependsOn) {
            //!!!! Invert the order !!!!
            dependencyOf.addEdge(dependency.r(), dependency.l(), new Object());
        }

        //collect types
        for (SolomonoffWeightedParser.ConcurrentCollector.Type type : collector.types) {
            if (type.isFunction) definitions.get(type.var).funcTypes.add(Pair.of(type.in, type.out));
            else definitions.get(type.var).productTypes.add(Pair.of(type.in, type.out));
        }

        //Resolve which dependencies need to be recompiled
        final Stack<VarDef<G>> toCheckIfNeedsRecompilation = new Stack<>();
        for (VarDef<G> def : definitions.values()) {
            if (!def.needsRecompilation) {
                toCheckIfNeedsRecompilation.add(def);
            }
        }
        while (!toCheckIfNeedsRecompilation.isEmpty()) {
            final VarDef<G> definition = toCheckIfNeedsRecompilation.pop();
            assert !definition.needsRecompilation;
            boolean actuallyShouldBeRecompiled = false;
            for (Object dependencyEdge : dependencyOf.incomingEdgesOf(definition.id)) {
                final String dependencyOfVertex = dependencyOf.getEdgeSource(dependencyEdge);
                final VarDef<G> definitionOfDependency = definitions.get(dependencyOfVertex);
                if (definitionOfDependency != null/*built-in variables don't need to recompiled*/
                        && definitionOfDependency.needsRecompilation) {
                    actuallyShouldBeRecompiled = true;
                    break;
                }
            }
            if (actuallyShouldBeRecompiled) {
                definition.needsRecompilation = true;
                for (Object dependedEdge : dependencyOf.outgoingEdgesOf(definition.id)) {
                    final String idToReconsider = dependencyOf.getEdgeTarget(dependedEdge);
                    final VarDef<G> definitionToReconsider = definitions.get(idToReconsider);
                    if (!toCheckIfNeedsRecompilation.contains(definitionToReconsider)) {
                        toCheckIfNeedsRecompilation.add(definitionToReconsider);
                    }
                }
            }
        }
        //At this point we know exactly which vertices need to be recompiled and which can
        //be taken from cache.

        //Topological order will allow for most efficient parallel compilation, because the dependencies
        //of every variable will be submitted for compilation at earlier stages.
        final TopologicalOrderIterator<String, Object> dependencyOrder = new TopologicalOrderIterator<>(dependencyOf);

        //compile all of them in parallel
        while (dependencyOrder.hasNext()) {
            final String id = dependencyOrder.next();
            final VarDef<G> varDef = definitions.get(id);

            if (varDef == null) {//This may only be true for built-in variables
                assert specs.specs.borrowVariable(id) != null : id;
            }else if(varDef instanceof VarDefAST){
                VarDefAST<G> var = (VarDefAST<G>) varDef;
                assert var.def != null : id;
                compiled.put(id, pool.submit(() -> {
                    if (var.needsRecompilation) {
                        final G compiledGraph = var.def.compile(specs.specs, i -> {
                            try {
                                final Future<G> f = compiled.get(i);
                                if (f == null) {//this can only be true for built-in variables
                                    final LexUnicodeSpecification.Var<N, G> v = specs.specs.copyVariable(i);
                                    assert v != null;
                                    final G graph = specs.specs.getGraph(v);
                                    return graph;
                                } else {
                                    final G graph = specs.specs.deepClone(f.get());
                                    return graph;
                                }
                            } catch (InterruptedException | ExecutionException e) {
                                throw new RuntimeException(e);
                            }
                        });

                        System.out.println("Compiled " + id);


                        try (FileOutputStream f = new FileOutputStream(var.cacheFilePath.toFile())) {
                            specs.specs.compressBinary(compiledGraph, new DataOutputStream(f));
                        } catch (IOException e) {
                            e.printStackTrace();
                            var.cacheFilePath.toFile().deleteOnExit();
                        }
                        return compiledGraph;
                    } else {
                        System.out.println("Loaded from cache " + id);
                        try (DataInputStream dis =
                                     new DataInputStream(new FileInputStream(var.cacheFilePath.toFile()))) {
                            return specs.specs.decompressBinary(Pos.NONE, dis);
                        }
                    }
                }));
            }
        }
        //wait for all
        for (Map.Entry<String, Future<G>> v : compiled.entrySet()) {
            final G g = v.getValue().get();
            final String id = v.getKey();
            assert specs.specs.borrowVariable(id) == null : id;
            System.out.println("Read to use " + id);
            specs.specs.introduceVariable(id, Pos.NONE, g, false);
        }
    }

    private static void importFromPackages() {
    }


    public static OptimisedLexTransducer.OptimisedHashLexTransducer
    getCompiler() throws CompilationError, ExecutionException, InterruptedException {
        final OptimisedLexTransducer.OptimisedHashLexTransducer compiler =
                new OptimisedLexTransducer.OptimisedHashLexTransducer(
                        0,
                        Integer.MAX_VALUE,
                        false);
        return compiler;
    }


}