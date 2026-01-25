# How to Run Tests

Execute the test suite to verify code correctness before contributing or deploying.

## Problem

You want to run tests to ensure your changes don't break existing functionality.

## Prerequisites

- Radiale is installed ([Getting Started](../tutorials/getting-started.md))
- Python dependencies installed: `pip install -e .`
- Python test dependencies: `pip install pytest pytest-cov pytest-mock`

## Steps

### 1. Run All Tests

Use the combined test script:

```bash
./run-tests.sh
```

This runs both Python and Clojure tests sequentially.

### 2. Run Python Tests Only

```bash
# All Python tests
pytest tests/

# With verbose output
pytest tests/ -v

# Specific test file
pytest tests/radiale/test_esphome.py

# Specific test function
pytest tests/radiale/test_pod.py::test_eprint -v
```

### 3. Run Clojure Tests Only

```bash
# All Clojure tests
clojure -M:test

# This uses the test runner at test/radiale/test_runner.clj
```

### 4. Run Tests with Coverage

For Python coverage:

```bash
pytest tests/ --cov=radiale --cov-report=term-missing
```

Output shows which lines are not covered:

```
Name                    Stmts   Miss  Cover   Missing
-----------------------------------------------------
radiale/pod.py            150     12    92%   45-48, 102
radiale/esphome.py        200     25    88%   78-82, 156-170
...
```

### 5. Run a Subset of Tests

Python tests by marker or pattern:

```bash
# Tests matching a pattern
pytest tests/ -k "mqtt"

# Skip slow tests (if marked)
pytest tests/ -m "not slow"
```

## Test Organization

### Python Tests (`tests/radiale/`)

| File | Tests |
|------|-------|
| `test_pod.py` | Babashka pod interface, bencode serialization |
| `test_esphome.py` | ESPHome client mocking, state handling |
| `test_deconz.py` | deCONZ API and WebSocket mocking |
| `test_mqtt.py` | MQTT client subscription logic |
| `test_mdns.py` | mDNS discovery mocking |
| `test_schedule.py` | Solar and crontab calculations |
| `test_chromecast.py` | Chromecast discovery and state |

### Clojure Tests (`test/radiale/`)

| File | Tests |
|------|-------|
| `core_test.clj` | Event loop, message processing |
| `state_test.clj` | State atom management, watches |
| `schedule_test.clj` | Scheduling functions |
| `scheduler_test.clj` | Job scheduler internals |
| `watch_test.clj` | Pattern matching logic |
| `esp_test.clj` | ESPHome wrapper functions |
| `deconz_test.clj` | deCONZ wrapper functions |

## Writing New Tests

### Python Test Example

```python
# tests/radiale/test_example.py
import pytest
from unittest.mock import patch, MagicMock

def test_my_function():
    """Test description."""
    result = my_function(input_value)
    assert result == expected_value

@patch("radiale.module.external_dependency")
def test_with_mock(mock_dep):
    """Test with mocked dependency."""
    mock_dep.return_value = "mocked"
    result = function_using_dependency()
    assert result == "mocked"
    mock_dep.assert_called_once()
```

### Clojure Test Example

```clojure
;; test/radiale/example_test.clj
(ns radiale.example-test
  (:require [clojure.test :refer :all]
            [radiale.example :as ex]))

(deftest test-my-function
  (testing "basic functionality"
    (is (= expected (ex/my-function input)))))

(deftest test-with-mock
  (testing "mocked pod call"
    (with-redefs [pod.xlfe.radiale/some-fn (constantly "mocked")]
      (is (= "mocked" (ex/function-using-pod))))))
```

## Continuous Integration

Tests run automatically on GitHub Actions for every push and pull request.

The CI workflow (`.github/workflows/ci.yml`):
1. Sets up Java 17, Python 3.9, Clojure CLI
2. Installs Python dependencies
3. Runs `./run-tests.sh`

Check CI status at: `https://github.com/xlfe/radiale/actions`

## Troubleshooting

### Python ImportError

Ensure the package is installed in editable mode:
```bash
pip install -e .
```

### Clojure test not found

Verify the test namespace follows the convention:
- Source: `src/radiale/foo.clj` (namespace `radiale.foo`)
- Test: `test/radiale/foo_test.clj` (namespace `radiale.foo-test`)

### Tests pass locally but fail in CI

- Check for environment-specific code (e.g., hardcoded paths)
- The `test_eprint` test handles systemd detection - similar patterns may be needed
- Ensure all dependencies are in `setup.py` or `deps.edn`

### Flaky tests

If tests occasionally fail:
- Add timeouts for async operations
- Mock external services rather than connecting
- Use `pytest-rerunfailures` for known flaky tests (last resort)

## See Also

- [Architecture](../explanation/architecture.md) - Understand code structure for testing
- [Python Modules](../reference/python-modules.md) - Module details for writing tests
- [Clojure API](../reference/clojure-api.md) - Function reference for test targets
