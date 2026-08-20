package com.Exam.Exam_System.service.judge;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The languages a candidate may answer in.
 *
 * The four TCS NQT offers, and the four every placement paper assumes. The
 * Judge0 ids are the published ones for its standard image; they are here
 * rather than in a config file because a wrong id does not fail loudly — it
 * silently compiles the candidate's Java as something else and marks them zero.
 */
public enum Language {

    C("c", "C (GCC 9)", 50, "main.c"),
    CPP("cpp", "C++ (GCC 9)", 54, "main.cpp"),
    JAVA("java", "Java 13", 62, "Main.java"),
    PYTHON("python", "Python 3.8", 71, "main.py");

    private final String id;
    private final String label;
    private final int judge0Id;
    private final String fileName;

    Language(String id, String label, int judge0Id, String fileName) {
        this.id = id;
        this.label = label;
        this.judge0Id = judge0Id;
        this.fileName = fileName;
    }

    public String id() { return id; }
    public String label() { return label; }
    public int judge0Id() { return judge0Id; }
    public String fileName() { return fileName; }

    public static Optional<Language> byId(String id) {
        if (id == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(l -> l.id.equalsIgnoreCase(id.trim()))
                .findFirst();
    }

    public static List<Language> all() { return Arrays.asList(values()); }

    /**
     * Java insists the public class matches the file name, and a candidate who
     * calls theirs Solution would otherwise fail to compile for a reason that
     * has nothing to do with the problem. The file is written as Main.java, so
     * the class is renamed to match rather than the candidate being marked down
     * for a filing convention.
     */
    public String normaliseSource(String source) {
        if (this != JAVA || source == null) return source;
        return source.replaceAll("public\\s+(final\\s+)?class\\s+\\w+", "public $1class Main");
    }
}
