# JGraphT-Builder

Builds graph-based representations of a Java project for type analysis, method-chain
search, and prompt augmentation for LLM-driven method body generation. Built on
[JGraphT](https://jgrapht.org/) and [JavaParser](https://javaparser.org/).

The project exposes **two graphs**:

### 1. Type Graph (`TypeGraph`)

Vertices are Java types (including parameterized generics like `List<String>`), edges
are typed relationships:

| Edge | Direction | Meaning |
|------|-----------|---------|
| **IS** | subtype → supertype | `extends` / `implements` |
| **HAS** | declaring type → field type | class has a field of this type |
| **CONSUMES** | parameter type → declaring type | a method of the declaring type takes this type as a parameter |
| **PRODUCES** | declaring type → return type | a method of the declaring type returns this type |

### 2. Global Composition Graph (`GlobalCompositionGraph`)

Built on top of the Type Graph, models methods as **multi-input operators (hyperedges)**
over typed and identified resources. Used for resource-aware retrieval of candidate
call chains for target methods *without* a body.

| Node kind | Meaning |
|-----------|---------|
| **TYPE** | a Java type |
| **METHOD** | a method as an operator with typed receiver + parameter slots |
| **FIELD** | a field of some declaring type |

| Edge kind | Direction | Meaning |
|-----------|-----------|---------|
| **CONSUMES** | TYPE → METHOD | the type fills a parameter slot (with `slotIndex`) |
| **RECEIVER** | TYPE → METHOD | the type is required as the receiver of an instance method |
| **PRODUCES** | METHOD → TYPE | the method returns this type |
| **READS_FIELD** | FIELD → METHOD | (Stage 3) the method reads this field on its return-producing chain |
| **EVIDENCE_CALLS** | METHOD → METHOD | (Stage 3) one method's result feeds into another in real code |

## Quick Start

```bash
# Build the project
./gradlew build

# Analyze a Java project and generate an interactive HTML viewer
./gradlew run --args="/path/to/java/project"

# Open the generated viewer in a browser
open typegraph.html
```

## Usage Examples

All examples below use `Call-Graph-Builder-For-Java/core-analyzer` as the target project.

### Interactive HTML Viewer (default)

```bash
./gradlew run --args="/Users/sckwoky/Projects/Call-Graph-Builder-For-Java/core-analyzer"
open typegraph.html
```

Generates a self-contained HTML file with [Cytoscape.js](https://js.cytoscape.org/) that supports:
- Search/filter vertices by name
- Toggle edge types (IS, HAS, CONSUMES, PRODUCES)
- Click a node to see its connections
- Relayout and zoom

### GraphViz DOT Export

```bash
./gradlew run --args="-f dot -o graph.dot /Users/sckwoky/Projects/Call-Graph-Builder-For-Java/core-analyzer"
dot -Tsvg graph.dot -o graph.svg
open graph.svg
```

### JSON Export

```bash
./gradlew run --args="-f json -o graph.json /Users/sckwoky/Projects/Call-Graph-Builder-For-Java/core-analyzer"
```

### Query Mode: Find Method Chains

Given input types and a desired output type, find chains of method calls connecting them:

```bash
# Find chains: String → some target type
./gradlew run --args="--no-jars -q 'java.lang.String->com.example.MyType' /path/to/project"

# Limit chain depth
./gradlew run --args="--no-jars -q 'java.lang.String->com.example.MyType' --max-depth 3 /path/to/project"
```

Example output:
```
Found 3 chain(s):
  1. Chain: java.lang.String → com.example.Owner.<init>() → com.example.Owner
  2. Chain: java.lang.String → com.example.Dog.<init>() → com.example.Dog.getOwner() → com.example.Owner
  3. Chain: java.lang.String → com.example.Owner.adoptDog() → com.example.Dog.getOwner() → com.example.Owner
```

### Self-Analysis

Analyze this project itself:

```bash
./gradlew run --args="--no-jars -f html -o self.html ."
open self.html
```

## Composition Graph & Candidate Chain Retrieval

The composition layer treats every method as an operator with typed receiver and
parameter slots. From a target method's signature alone (no body), it finds and
ranks candidate call chains that produce the desired return type using only the
resources actually available (params, fields, `this`).

### Interactive Composition Graph Viewer

Export the global composition graph and explore every TYPE / METHOD / FIELD node
in a self-contained Cytoscape.js HTML page. Diamond-shaped nodes are METHOD
operators, rounded rectangles are TYPEs, ellipses are FIELDs. The viewer supports
search, node-kind and edge-kind filtering, three layout modes (force / hierarchy /
concentric), and a click-info panel showing the full signature, declaring type,
return type, and parameter list of any node.

```bash
./gradlew run --args="--no-jars --export-composition -f html -o composition src/test/resources/fixtures"
open composition.html
```

JSON export of the same graph (Cytoscape.js elements format):

```bash
./gradlew run --args="--no-jars --export-composition -f json -o composition.json src/test/resources/fixtures"
```

### Find Candidate Chains for a Target Method (no body)

Format the target signature as
`DeclaringType#methodName(Type1 name1, Type2 name2)->ReturnType`. Field names of
`DeclaringType` are picked up automatically from the source. Output formats:
`prompt` (default, LLM-friendly text) or `json`.

```bash
# Synthetic target on the bundled fixtures: produce a Dog from (String, Integer)
./gradlew run --args="--no-jars --find-chains 'com.example.OwnerFactory#createDog(java.lang.String name, java.lang.Integer age)->com.example.Dog' --top-k 5 -f prompt src/test/resources/fixtures"
```

Sample output:

```
Target: com.example.OwnerFactory#createDog(java.lang.String, java.lang.Integer) -> com.example.Dog
Available:
  - param: name (java.lang.String)
  - param: age (java.lang.Integer)
  - this:  com.example.OwnerFactory

Candidate 1 [HIGH, score=0.82]
  steps:
    1. new Dog(name, age)  -> Dog
  notes: no evidence (Stage 1: structural-only ranking)

Candidate 2 [HIGH, score=0.73]
  steps:
    1. new Owner(name)  -> Owner
    2. $1.adoptDog(name, age)  -> Dog
  notes: no evidence (Stage 1: structural-only ranking)
```

JSON output (writes to `chains.json`):

```bash
./gradlew run --args="--no-jars --find-chains 'com.example.OwnerFactory#createDog(java.lang.String name, java.lang.Integer age)->com.example.Dog' -f json -o chains src/test/resources/fixtures"
```

### Visualize the Chains in the Composition Graph

Pass `-f html` together with `--find-chains` to get a self-contained interactive
viewer that shows the **full composition graph and a sidebar with every ranked
candidate chain**. Click any chain card to highlight the exact path it takes
through the graph: the involved TYPE, METHOD and FIELD nodes get a blue border
and the connecting `CONSUMES` / `RECEIVER` / `PRODUCES` edges become bold blue.
Everything else fades out so the chain is easy to follow visually. Click
"Clear highlight" (or pick another candidate) to switch.

```bash
./gradlew run --args="--no-jars --find-chains 'com.example.OwnerFactory#createDog(java.lang.String name, java.lang.Integer age)->com.example.Dog' --top-k 5 -f html -o chains src/test/resources/fixtures"
open chains.html
```

Inside the viewer:

- **Sidebar (top-right)** — every candidate chain with its confidence label,
  numeric score, the human-readable steps (e.g. `1. new Dog(name, age) → Dog`)
  and a one-line evidence summary. Click a card to highlight that chain.
- **Graph (background)** — the full composition graph (TYPE = rounded rectangle,
  METHOD = diamond, FIELD = ellipse) with the layout, search and filter controls
  on the left.
- **Info panel (bottom-right)** — appears on node click and shows the full
  signature, declaring type, return type, parameter types, etc. for any node.

This is the recommended way to inspect *why* a particular chain was proposed: you
can see exactly which fields and parameters feed into which methods, and how the
produced types are threaded together to reach the target return type.

### How chains are scored (Stage 1)

Each candidate is validated by `ExecutionPlanValidator` (every slot must be bound;
produced values must respect topological order; final type must match the target's
return type) and then scored by `ChainRanker` along three components:

| Component | What it measures |
|-----------|------------------|
| **structural** | resource coverage (slots bound to real params/fields vs produced values) + length penalty + slot match quality |
| **reliability** | fraction of `EXACT`/`SUBTYPE` slot matches; `ERASURE_FALLBACK` and `UNRESOLVED` are penalized |
| **locality** | bonus for methods declared in the same class / package as the target |

Total score buckets into **HIGH**, **MEDIUM**, or **LOW** confidence labels that are
included in the prompt output as an explicit reliability marker for downstream LLMs.

> **Note**: candidate chains are *skeletal data paths* — the core value-producing
> operations only. They do not model null-checks, validation, error handling, or
> logging. They are retrieval augmentation, not finished method bodies.

## Method Flow Graphs (intraprocedural control + data flow)

The composition graph treats method bodies as opaque, so it cannot show what
happens *inside* a method — branches, loops, try/catch, field reads, etc. The
**Method Flow Graph** layer parses each method body and produces a per-method
graph with the following node kinds:

| Group | Kinds |
|-------|-------|
| **Inputs** | `PARAM`, `THIS_REF`, `FIELD_READ` |
| **Locals** | `LOCAL_DEF`, `LOCAL_USE`, `TEMP_EXPR`, `LITERAL` |
| **Calls** | `CALL`, `CALL_RESULT` (split: the call operation vs. its produced value) |
| **Side effects** | `FIELD_WRITE`, `RETURN` |
| **Control** | `BRANCH` (subtype: `IF`, `SWITCH`, `TRY`, `CATCH`, `FINALLY`, `TERNARY`), `LOOP` (subtype: `FOR`, `FOREACH`, `WHILE`, `DO`), `MERGE`, `MERGE_VALUE` (phi-like) |

Edges: `DATA_DEP`, `ARG_PASS`, `CALL_RESULT_OF`, `RETURN_DEP`, `DEF_USE`,
`PHI_INPUT`, `CONTROL_DEP`. Variable assignments are versioned (SSA-lite); after
an `if/switch/try/loop` the builder produces explicit `MERGE_VALUE` (phi) nodes
joining the per-branch versions, so the slicer can follow data dependencies
across control flow.

### Build & Visualize Flow Graphs for All Methods

```bash
# Builds a flow graph for every method in the project and writes an interactive
# multi-method viewer to /tmp/flow/index.html plus one .js file per method.
./gradlew run --args="--no-jars --flow-graphs -o /tmp/flow src/test/resources/fixtures"
open /tmp/flow/index.html
```

Restrict to one class or package with `--scope`:

```bash
./gradlew run --args="--no-jars --flow-graphs --scope com.example.OwnerHelper -o /tmp/flow src/test/resources/fixtures"
```

### Inside the Viewer

The output directory has the structure:

```
/tmp/flow/
  index.html              ← open this in a browser
  flow_data/
    m0.js, m1.js, …       ← one per method, lazy-loaded via JSONP-style
                            <script> tags so it works from file:// without an
                            HTTP server
```

Features:

- **Sidebar** — every method in the project, grouped by package and class. Each
  entry shows compact badges with the number of branches, loops, calls and
  returns inside that method body. Click any method to load its flow graph.
- **Graph canvas** — Cytoscape.js renderer with distinct shapes per node kind:
  diamonds for `CALL` / `BRANCH`, hexagons for `MERGE` / `MERGE_VALUE` / `LOOP`,
  octagons for `RETURN`, tags for `FIELD_READ` / `FIELD_WRITE`, ellipses for
  `CALL_RESULT`, etc. Edges are color- and style-coded by kind (`CONTROL_DEP`
  dashed, `PHI_INPUT` dotted purple, `ARG_PASS` orange, `RETURN_DEP` dark red).
- **Backward slice mode** — toggle "Highlight backward slice", pick a `RETURN`
  node from the dropdown, and the viewer fades everything except the slice that
  reaches that return. Includes only control nodes that actually dominate
  data-relevant nodes (so a `BRANCH` only appears if its body contributes to
  the return).
- **CALL navigation** — click any `CALL` node; if its callee was also analyzed
  (and therefore present in the sidebar), the info panel shows a "→ Jump to
  callee flow graph" button that switches the canvas to the callee.
- **Filters** — checkboxes to hide whole node kinds or edge kinds. Useful for
  reducing visual noise (e.g. hide `LOCAL_DEF` and `TEMP_EXPR` to see only the
  call structure).
- **Search** — type any substring (label, id, call signature) to fade
  non-matching nodes.

### Documented limitations

The flow graph is an **approximation**, not a sound static analyzer:

- **No alias analysis**: `var d = this.field; modify(d);` does not link `d`
  back to `this.field`.
- **No precise exception flow**: try/catch is over-approximated (any catch is
  reachable from the try entry).
- **Loops are processed once**: loop-carried dependencies collapse into a
  single phi merge between pre- and post-iteration versions.
- **Lambda bodies are opaque** (the lambda becomes a single CALL with
  `UNRESOLVED` resolution).
- **Arrays are monolithic**: element-wise tracking is not modeled.
- **CALL signatures may be UNRESOLVED** when the symbol solver cannot resolve
  them — they still appear in the graph but with `callResolution=UNRESOLVED`.

These are intentional design constraints for an MVP that prioritizes
visualization and evidence mining over soundness.

## CLI Options

```
Usage: typegraph [-hV] [--no-jars] [-f=<format>] [--max-depth=<maxDepth>]
                 [-o=<output>] [-q=<query>]
                 [--find-chains=<spec>] [--top-k=<k>]
                 [--export-composition]
                 <projectDir>

      <projectDir>            Path to the Java project to analyze
  -f, --format=<format>       Export format: dot, json, html (default: html);
                              for --find-chains: prompt or json
  -o, --output=<output>       Output file path (default: typegraph)
      --no-jars               Skip JAR dependency resolution
  -q, --query=<query>         Type-graph chains: inputType1,inputType2->outputType
      --max-depth=<depth>     Max chain depth (default: 5)
      --find-chains=<spec>    Composition retrieval: candidate chains for a target
                              method without a body. Format:
                              DeclType#name(T1 a, T2 b)->Return
      --top-k=<k>             Top-K candidate chains (default: 10)
      --export-composition    Export the global composition graph (TYPE/METHOD/FIELD)
                              as html or json
      --flow-graphs           Build per-method intraprocedural flow graphs and
                              export them as a multi-method interactive HTML viewer
      --scope=<prefix>        Restrict --flow-graphs / --find-chains to a class
                              FQN or package prefix
  -h, --help                  Show help message and exit
  -V, --version               Print version info and exit
```

## Requirements

- Java 25+
- Gradle 9.x (wrapper included)
- [GraphViz](https://graphviz.org/) (optional, for DOT rendering)
