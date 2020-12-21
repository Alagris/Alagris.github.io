package net.alagris;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import org.jgrapht.graph.*;

public class Compiler {

    public static void main(String[] args) {
        StringBuilder contentBuilder = new StringBuilder();

        try (Stream<String> stream = Files.lines(Paths.get("sample.mealy"), StandardCharsets.UTF_8))
        {
            stream.forEach(s -> contentBuilder.append(s).append("\n"));
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        String source = contentBuilder.toString();
        final GrammarLexer lexer = new GrammarLexer(CharStreams.fromString(source));
        final GrammarParser parser = new GrammarParser(new CommonTokenStream(lexer));
        DirectedAcyclicGraph<Token, DefaultEdge> dag = new DirectedAcyclicGraph<>(DefaultEdge.class);
        final LinkedList<CompilationError> errors = new LinkedList<>();
        ParserListener listener = new ParserListener(dag, errors);
        ParseTreeWalker.DEFAULT.walk(listener, parser.start());
        

    }

}
