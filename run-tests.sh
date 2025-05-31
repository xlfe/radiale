#!/bin/bash
echo "Running Python tests..."
pytest tests/
PY_EXIT_CODE=$?

echo ""
echo "Running Clojure tests..."
clojure -X:test
CLJ_EXIT_CODE=$?

if [ $PY_EXIT_CODE -ne 0 ] || [ $CLJ_EXIT_CODE -ne 0 ]; then
  echo ""
  echo "-----------------"
  echo " TESTS FAILED! "
  echo "-----------------"
  exit 1
else
  echo ""
  echo "-----------------"
  echo "ALL TESTS PASSED!"
  echo "-----------------"
  exit 0
fi
