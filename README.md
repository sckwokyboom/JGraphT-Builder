# JGraphT-Builder

Builds a type-relationship graph from a Java project's source code using [JGraphT](https://jgrapht.org/) and [JavaParser](https://javaparser.org/).

Vertices are Java types (including parameterized generics like `List<String>`), edges are relationships:

| Edge | Direction | Meaning |
|------|-----------|---------|
| **IS** | subtype → supertype | `extends` / `implements` |
| **HAS** | declaring type → field type | class has a field of this type |
| **CONSUMES** | parameter type → declaring type | a method of the declaring type takes this type as a parameter |
| **PRODUCES** | declaring type → return type | a method of the declaring type returns this type |

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

## CLI Options

```
Usage: typegraph [-hV] [--no-jars] [-f=<format>] [--max-depth=<maxDepth>]
                 [-o=<output>] [-q=<query>] <projectDir>

      <projectDir>       Path to the Java project to analyze
  -f, --format=<format>  Export format: dot, json, html (default: html)
  -h, --help             Show help message and exit
      --max-depth=<depth> Maximum chain depth for queries (default: 5)
      --no-jars          Skip JAR dependency resolution
  -o, --output=<output>  Output file path (default: typegraph)
  -q, --query=<query>    Find chains: inputType1,inputType2->outputType
  -V, --version          Print version info and exit
```

## Requirements

- Java 25+
- Gradle 9.x (wrapper included)
- [GraphViz](https://graphviz.org/) (optional, for DOT rendering)
