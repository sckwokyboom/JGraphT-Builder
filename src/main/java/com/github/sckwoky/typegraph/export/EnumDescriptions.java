package com.github.sckwoky.typegraph.export;

/**
 * Single source of truth for human-readable names, descriptions and examples
 * of every enum constant rendered in the HTML viewers. Produces a JavaScript
 * object literal that composition and flow viewers embed verbatim.
 * <p>
 * UI usage: legends and filter checkboxes show {@code name} instead of the
 * raw constant, and a hover popover shows {@code description}, the original
 * constant name, and {@code example}.
 */
public final class EnumDescriptions {

    private EnumDescriptions() {}

    public static String toJs() {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"compositionNode\": ").append(compositionNode()).append(",\n");
        sb.append("  \"compositionEdge\": ").append(compositionEdge()).append(",\n");
        sb.append("  \"flowNode\": ").append(flowNode()).append(",\n");
        sb.append("  \"flowEdge\": ").append(flowEdge()).append(",\n");
        sb.append("  \"controlSubtype\": ").append(controlSubtype()).append(",\n");
        sb.append("  \"callResolution\": ").append(callResolution()).append(",\n");
        sb.append("  \"fieldOrigin\": ").append(fieldOrigin()).append("\n");
        sb.append("}");
        return sb.toString();
    }

    // ─── Composition graph ──────────────────────────────────────────────

    private static String compositionNode() {
        return obj(
                entry("TYPE", "Тип",
                        "Java-тип — вершина композиционного графа. Включает generics.",
                        "java.lang.String, com.example.Owner, List<Dog>"),
                entry("METHOD", "Метод-оператор",
                        "Метод как hyperedge с receiver-слотом и параметрами. Применим только если все слоты покрыты available resources.",
                        "Owner#adoptDog(String, int) → Dog"),
                entry("FIELD", "Поле класса",
                        "Именованное поле declaring-класса с известным типом.",
                        "Owner.dogs : List<Dog>")
        );
    }

    private static String compositionEdge() {
        return obj(
                entry("CONSUMES", "Параметр-слот",
                        "TYPE → METHOD: тип подаётся как параметр метода; label хранит индекс слота.",
                        "String → Dog#<init>  [slot 0]"),
                entry("RECEIVER", "Receiver-слот",
                        "TYPE → METHOD: instance-метод требует receiver этого типа.",
                        "Owner → Owner#adoptDog"),
                entry("PRODUCES", "Возвращает значение",
                        "METHOD → TYPE: метод возвращает этот тип.",
                        "Owner#adoptDog → Dog"),
                entry("READS_FIELD", "Читает поле на return-цепочке",
                        "FIELD → METHOD: поле реально участвует в формировании возвращаемого значения (подтверждено flow analysis). Заполняется на Stage 3.",
                        "Owner.dogs → Owner#getDogs"),
                entry("EVIDENCE_CALLS", "Подтверждённый вызов",
                        "METHOD_A → METHOD_B: в реальном коде результат A передавался во вход B. Заполняется на Stage 3.",
                        "findById → toDto через Owner")
        );
    }

    // ─── Flow graph — nodes ─────────────────────────────────────────────

    private static String flowNode() {
        return obj(
                entry("PARAM", "Параметр метода",
                        "Входной параметр анализируемого метода. Корневой источник данных для слайсера.",
                        "public Dog adopt(String name, int age) — сюда попадут name и age"),
                entry("THIS_REF", "Ссылка this",
                        "Неявная ссылка на enclosing instance. Receiver для обращений к полям и instance-методам.",
                        "this, this.field"),
                entry("FIELD_READ", "Чтение поля",
                        "Обращение на чтение к полю. FieldOrigin показывает, принадлежит ли поле this, другому объекту или static-классу.",
                        "this.repo, dog.name, Integer.MAX_VALUE"),
                entry("FIELD_WRITE", "Запись в поле",
                        "Присваивание полю класса.",
                        "this.name = value;"),
                entry("LOCAL_DEF", "Определение локальной переменной",
                        "Versioned SSA-lite определение: каждое присваивание создаёт новую версию. Версия отображается в variableVersion.",
                        "var x = repo.findById(id);"),
                entry("LOCAL_USE", "Чтение локальной переменной",
                        "Зарезервированный kind для явных событий чтения. В текущей реализации не создаётся — analyzeName возвращает текущий LOCAL_DEF напрямую.",
                        "return x;  (сейчас ссылается напрямую на LOCAL_DEF x)"),
                entry("TEMP_EXPR", "Промежуточное значение",
                        "Результат бинарной/унарной/cast/array-access операции. Используется как плейсхолдер между source-level выражениями и data-edges.",
                        "a + b, (Dog) obj, arr[i]"),
                entry("MERGE_VALUE", "Phi-слияние версий переменной",
                        "Phi-подобный узел, объединяющий версии переменной, пришедшие из разных веток if/switch/try/loop. Входы — PHI_INPUT рёбра.",
                        "if (c) x = a(); else x = b(); return x; → phi(x_v0, x_v1)"),
                entry("CALL", "Операция вызова",
                        "Site вызова метода или конструктора — side-effect point. Сюда приходят ARG_PASS рёбра от аргументов и receiver'а. callResolution показывает степень разрешения сигнатуры.",
                        "repo.findById(id), new Dog(name, age)"),
                entry("CALL_RESULT", "Значение-результат вызова",
                        "Produced value, которое вернула CALL. Отделён от самого CALL, чтобы корректно моделировать chained calls.",
                        "a().b().c() — три CALL/CALL_RESULT пары"),
                entry("RETURN", "Return-оператор",
                        "Выход из метода. Точка старта для backward slicer. Каждый return — свой RETURN узел.",
                        "return result;"),
                entry("BRANCH", "Ветвление управления",
                        "Control-узел для if/switch/try/catch/finally/ternary. Конкретный вид — в controlSubtype. Условие подаётся DATA_DEP ребром.",
                        "if (cond), switch (x), try { }, cond ? a : b"),
                entry("MERGE", "Слияние control flow",
                        "Control-узел, отмечающий точку схождения веток после BRANCH. Дочерние phi-узлы (MERGE_VALUE) прикреплены к MERGE через CONTROL_DEP.",
                        "закрывающая } после if/else или try/catch"),
                entry("LOOP", "Цикл (summary-узел)",
                        "Единый summary-узел для for/foreach/while/do-while. Тело цикла не разворачивается по итерациям; loop-carried зависимости аппроксимируются одним phi-слиянием.",
                        "for (Dog d : dogs), while (cond)"),
                entry("LITERAL", "Литерал",
                        "Константа в исходнике: строковая/числовая/boolean/null.",
                        "\"hello\", 42, null, true")
        );
    }

    // ─── Flow graph — edges ─────────────────────────────────────────────

    private static String flowEdge() {
        return obj(
                entry("DATA_DEP", "Data dependency",
                        "Обобщённая зависимость значения. Source-узел вычисляет value, target-узел его использует.",
                        "rhs_expr → LOCAL_DEF(x)"),
                entry("ARG_PASS", "Передача в CALL",
                        "Данные, входящие в CALL как аргумент (label = arg[i]) или как receiver (label = receiver).",
                        "param(name) → CALL(findById)  [arg[0]]"),
                entry("CALL_RESULT_OF", "Структурное ребро CALL→CALL_RESULT",
                        "Связывает операцию вызова с её produced value. Всегда ровно одно ребро на каждую CALL/CALL_RESULT пару.",
                        "CALL(findById) → CALL_RESULT(Owner)"),
                entry("RETURN_DEP", "Зависимость return",
                        "Значение, уходящее в RETURN узел. По этим рёбрам слайсер стартует backward-обход.",
                        "MERGE_VALUE(result) → RETURN"),
                entry("DEF_USE", "Def-use",
                        "LOCAL_DEF → LOCAL_USE. Появится, когда включим явные LOCAL_USE события.",
                        "x_v0 → LOCAL_USE(x)"),
                entry("PHI_INPUT", "Вход phi-слияния",
                        "Конкретная версия переменной, входящая в MERGE_VALUE после if/loop/try.",
                        "x_v0 → phi(x);  x_v1 → phi(x)"),
                entry("CONTROL_DEP", "Control dependency",
                        "BRANCH/LOOP/MERGE → узел, попадающий под его control-область. Используется слайсером для включения dominating control узлов.",
                        "BRANCH(if) → CALL inside then-branch")
        );
    }

    // ─── Control subtype ────────────────────────────────────────────────

    private static String controlSubtype() {
        return obj(
                entry("IF", "if / else", "BRANCH/MERGE для if-else оператора.", "if (cond) { … } else { … }"),
                entry("SWITCH", "switch", "BRANCH/MERGE для switch.", "switch (x) { case A: … }"),
                entry("TRY", "try-блок", "BRANCH корневой для try/catch, MERGE схождения всех веток.", "try { … }"),
                entry("CATCH", "catch-ветка", "Отдельный BRANCH на каждый catch clause.", "catch (IOException e) { … }"),
                entry("FINALLY", "finally-блок", "BRANCH, выполняемый после merge try+catch.", "finally { cleanup(); }"),
                entry("TERNARY", "?: тернарный оператор", "BRANCH/MERGE для ?: выражения; результат — MERGE_VALUE.", "cond ? a : b"),
                entry("FOR", "for (C-style)", "LOOP-узел для классического for с init/cond/update.", "for (int i = 0; i < n; i++) { … }"),
                entry("FOREACH", "for-each", "LOOP-узел для расширенного for.", "for (Dog d : dogs) { … }"),
                entry("WHILE", "while", "LOOP-узел для while.", "while (cond) { … }"),
                entry("DO", "do-while", "LOOP-узел для do-while (условие внизу).", "do { … } while (cond);"),
                entry("NONE", "(не control-узел)", "Значение по умолчанию для не-control узлов.", "—")
        );
    }

    // ─── Call resolution ────────────────────────────────────────────────

    private static String callResolution() {
        return obj(
                entry("RESOLVED", "Полностью разрешён",
                        "Symbol solver вернул полную MethodSignature (declaringType, paramTypes, returnType).",
                        "repo.findById(id) → Owner"),
                entry("PARTIALLY_RESOLVED", "Частично разрешён",
                        "Известно имя метода и арность, но типы параметров или declaring-тип не определены точно.",
                        "unknown.foo(x, y)  — foo известен, типы x и y — нет"),
                entry("UNRESOLVED", "Не разрешён",
                        "Symbol solver бросил исключение. Только текст вызова известен. Сильно штрафуется в reliability-скоринге.",
                        "library.method(...) без подключённого JAR")
        );
    }

    // ─── Field origin ───────────────────────────────────────────────────

    private static String fieldOrigin() {
        return obj(
                entry("THIS", "Поле this", "Поле текущего enclosing-класса. Квалифицированное this.x или неквалифицированное x.", "this.name, repo (unqualified)"),
                entry("OTHER", "Поле другого объекта", "Поле на непосредственном receiver'е, отличном от this.", "dog.name, owner.dogs"),
                entry("STATIC", "Static-поле", "Поле класса, обращение через имя класса.", "Integer.MAX_VALUE, Math.PI"),
                entry("UNKNOWN", "Источник неизвестен", "Не удалось определить, чьё это поле.", "—")
        );
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private record Entry(String constant, String name, String description, String example) {}

    private static Entry entry(String constant, String name, String description, String example) {
        return new Entry(constant, name, description, example);
    }

    private static String obj(Entry... entries) {
        var sb = new StringBuilder("{\n");
        for (int i = 0; i < entries.length; i++) {
            var e = entries[i];
            sb.append("    ").append(jsStr(e.constant)).append(": {")
                    .append("\"name\": ").append(jsStr(e.name)).append(", ")
                    .append("\"description\": ").append(jsStr(e.description)).append(", ")
                    .append("\"example\": ").append(jsStr(e.example))
                    .append("}");
            if (i < entries.length - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  }");
        return sb.toString();
    }

    private static String jsStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }
}
