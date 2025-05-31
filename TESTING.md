# Testing Strategy for Radiale

This document outlines the testing strategy for the Radiale home automation project, covering both Python and Clojure codebases, as well as their interaction. The primary goal is to ensure code reliability, maintainability, and correctness through a combination of unit and integration tests.

## 1. Overall Strategy

Radiale aims for a robust testing approach:
*   **Unit Tests:** To verify the functionality of individual modules (Python) and namespaces (Clojure) in isolation.
*   **Integration Tests:** To ensure that different components within the Python and Clojure parts work together as expected.
*   **Interaction Tests:** To validate the communication and contract between the Clojure and Python parts, primarily focusing on the Babashka pod interface.

## 2. Python Testing Strategy

### Framework
*   **`pytest`**: A mature and feature-rich Python testing framework will be used for its ease of use, powerful fixture model, and extensive plugin ecosystem.

### Directory Structure
*   Python tests will reside in the `tests/radiale/` directory, mirroring the structure of the `radiale/` package.
    ```
    tests/
    └── radiale/
        ├── __init__.py
        ├── test_pod.py
        ├── test_mqtt.py
        ├── test_esphome.py
        └── ...
    ```

### Unit Tests
*   Each Python module in `radiale/` (e.g., `mqtt.py`, `esphome.py`, `pod.py`) should have a corresponding test file (e.g., `test_mqtt.py`).
*   Tests should focus on the public API of the modules.
*   External dependencies (like network services or hardware) will be mocked.

### Mocking
*   **`unittest.mock`** (from the standard library) or the **`pytest-mock`** plugin (which provides a `mocker` fixture) will be used for creating mock objects and patching dependencies.
*   Example: Mocking `aioesphomeapi.APIClient` for `esphome.py` tests or `websockets.connect` for `deconz.py`.

### Basic Integration Tests
*   Limited integration tests might be included to verify interactions between closely related Python modules, still within the Python environment and potentially using mocks for external systems. For instance, testing the flow between `mdns.py` discovering a service and `esphome.py` attempting a connection to a mocked discovered service.

### Execution
*   Tests can be run using the `pytest` command from the project root:
    ```bash
    pytest tests/radiale/
    ```

### Coverage
*   Test coverage will be measured using `pytest-cov`:
    ```bash
    pytest --cov=radiale tests/radiale/
    ```
    The aim should be to maintain a high level of test coverage.

## 3. Clojure Testing Strategy

### Framework
*   **`clojure.test`**: The standard testing library built into Clojure will be used.

### Directory Structure
*   Clojure tests will reside in a `test/` directory, mirroring the `src/` structure:
    ```
    test/
    └── radiale/
        ├── core_test.clj
        ├── schedule_test.clj
        └── ...
    ```
*   Each namespace (e.g., `radiale.core`) should have a corresponding test namespace (e.g., `radiale.core-test`).

### Unit Tests
*   Tests will focus on individual functions within each namespace.
*   Pure functions will be tested with various inputs and expected outputs.
*   Functions with side effects or those interacting with state will require careful setup and mocking.

### Mocking
*   **`with-redefs`**: Clojure's built-in `with-redefs` macro will be used to temporarily redefine functions or vars for mocking purposes. This is particularly useful for mocking calls to the Python pod or other external systems.
    *   Example: In `radiale.esp-test`, one might `with-redefs` the pod invocation function (`pod.xlfe.radiale/subscribe-esp*`) to return a predefined value or a mock channel.

### Fixtures
*   `clojure.test` fixtures (`use-fixtures`) can be used for setting up and tearing down test state on a per-namespace or per-test basis (e.g., initializing a test state atom).

### Execution
*   Tests will be run using the Clojure CLI with a dedicated test alias in `deps.edn`.
*   First, add a test alias to `deps.edn`:
    ```clojure
    ;; In deps.edn
    {:aliases
     {;; ... other aliases
      :test {:extra-paths ["test"]
             :extra-deps {;; Add any test-specific dependencies if needed
                         }
             :main-opts ["-m" "clojure.test.run"]}}} ; Or use a test runner
    ```
*   Then, execute tests with:
    ```bash
    clojure -X:test
    ```
    (Or `clojure -M:test` if not using `-X` execution, depending on the test runner setup. For simple `clojure.test` execution without a custom runner, you might need to specify namespaces or use a small test runner script.)
    A common approach is to use a test runner like Kaocha or Cognitect's test-runner for more features, which would also be configured in the alias. For `clojure.test.run`, you might need to specify namespaces to test in the alias or invoke it differently. A more robust alias might be:
    ```clojure
    ;; deps.edn alias for running all tests
    :test {:extra-paths ["test"]
           :main-opts ["-m" "clojure.main" "-e" "(require 'radiale.core-test 'radiale.schedule-test) (clojure.test/run-tests 'radiale.core-test 'radiale.schedule-test)"]}
    ```
    (This example requires manually listing test namespaces. A test runner automates this.)


## 4. Clojure-Python Interaction Testing (Pod Interface)

Testing the interaction between Clojure and Python via the Babashka pod presents unique challenges.

### Challenges
*   Direct end-to-end testing can be complex due to the asynchronous nature and the need for both runtimes.
*   Ensuring data passed via bencode matches expectations on both sides.

### Contract Testing (Schema Validation)
*   Define schemas (e.g., using Malli for Clojure, or a similar validation approach in Python) for messages passed between Clojure and Python.
*   Validate messages against these schemas at the boundaries (Clojure sending, Python receiving, and vice-versa). This helps catch issues if message structures diverge.

### Mocking the Pod Boundary
*   **Clojure side:** Use `with-redefs` to mock the Babashka pod invocation functions (e.g., `pod.xlfe.radiale/listen-mqtt*`). The mock can simulate responses from the Python side, allowing Clojure logic to be tested independently of a running Python pod.
*   **Python side:** When testing functions in `radiale/pod.py` that are invoked by Clojure, mock the `sys.stdin.buffer` (for bdecoded messages) and `sys.stdout.buffer` (for bencoded responses) or the `OutgoingQ` behavior to simulate Clojure requests and verify Python responses without needing a Clojure runtime.

### Limited End-to-End Style Tests
*   A small set of carefully crafted tests could run both Clojure and Python, initiating an action from the Clojure side and verifying the outcome in Python (or vice-versa). These tests would be more complex to set up and maintain.
*   This might involve:
    1.  Starting the Python pod script (`pod-xlfe-radiale.py`) as a subprocess.
    2.  Running a Clojure test function that interacts with this pod.
    3.  Capturing and asserting stdout/stderr or other side effects from the Python pod.

## 5. Test Execution and CI

### Combined Test Script
*   A shell script (e.g., `run-tests.sh`) can be created to execute both Python and Clojure tests with a single command:
    ```bash
    #!/bin/bash
    echo "Running Python tests..."
    pytest tests/radiale/ --cov=radiale || { echo "Python tests failed"; exit 1; }

    echo "Running Clojure tests..."
    clojure -X:test || { echo "Clojure tests failed"; exit 1; } # Adjust if not using -X

    echo "All tests passed!"
    ```

### GitHub Actions Example for CI
*   Automate test execution using GitHub Actions. A simplified workflow:
    ```yaml
    # .github/workflows/ci.yml
    name: CI

    on: [push, pull_request]

    jobs:
      test:
        runs-on: ubuntu-latest
        steps:
          - uses: actions/checkout@v3
          - name: Set up Java
            uses: actions/setup-java@v3
            with:
              distribution: 'temurin' # or your preferred distribution
              java-version: '17' # or your project's version
          - name: Set up Clojure
            uses: DeLaGuardo/setup-clojure@12.1
            with:
              cli: 'latest' # or specific version
          - name: Set up Python
            uses: actions/setup-python@v4
            with:
              python-version: '3.9'
          - name: Install Python dependencies
            run: |
              python -m pip install --upgrade pip
              pip install -e .
              pip install pytest pytest-cov
          - name: Run tests
            run: ./run-tests.sh # Assuming you have the script
    ```

### Pre-commit Hooks
*   Consider using pre-commit hooks (e.g., with `pre-commit` framework) to run linters and tests automatically before committing code. This helps catch issues early.

This testing strategy provides a foundation for ensuring Radiale's quality. It should be adapted and expanded as the project evolves.
