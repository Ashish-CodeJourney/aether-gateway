# Phase 09 (M6) / PRD section 16.3: `make bench` regenerates every
# number BENCHMARKS.md claims from a clean checkout, in one command.
# See docs/plan/09-milestone-m6-benchmarking.md (gitignored, internal
# planning material) for the full task breakdown this implements.

SHELL := /bin/bash
GRADLE := ./gradlew
GATEWAY_DIR := aether-gateway
RESULTS_DIR := benchmark/results
SCRIPTS_DIR := benchmark/scripts
TOOLS_DIR := .tools
K6_VERSION := v2.1.0
K6_BIN := $(TOOLS_DIR)/k6-$(K6_VERSION)-linux-amd64/k6

.PHONY: bench verify-behavior experiments-corpus experiments-load ac-scorecard benchmarks-md k6-install docker-up docker-down

# ---- Regression gate (task 10a) ----
# Every prior build phase (03-08) contributed tagged Cucumber scenarios
# (@m0-@m5) to gateway-acceptance-tests. A regression here means a
# later phase broke an earlier phase's guarantee, and must be fixed
# before make bench's numbers mean anything.
verify-behavior:
	cd $(GATEWAY_DIR) && $(GRADLE) :gateway-acceptance-tests:test

# ---- Full suite (task 10) ----
bench: verify-behavior experiments-corpus docker-up k6-install experiments-load ac-scorecard benchmarks-md
	@echo "make bench complete. See BENCHMARKS.md and $(RESULTS_DIR)/*.csv."

# Experiments 1-4 and 8: pure JVM (JUnit + real embedding model +
# Testcontainers Postgres for experiment 4), no docker-compose/k6 needed.
experiments-corpus:
	cd $(GATEWAY_DIR) && $(GRADLE) :gateway-bench:integrationTest --tests "*ThresholdSweepExperimentTest*"
	cd $(GATEWAY_DIR) && $(GRADLE) :gateway-bench:integrationTest --tests "*EntityGuardAblationExperimentTest*"
	cd $(GATEWAY_DIR) && $(GRADLE) :gateway-bench:integrationTest --tests "*EmbeddingModelComparisonExperimentTest*"
	cd $(GATEWAY_DIR) && $(GRADLE) :gateway-bench:integrationTest --tests "*HnswParameterTuningExperimentTest*"
	cd $(GATEWAY_DIR) && $(GRADLE) :gateway-bench:integrationTest --tests "*CostSimulationExperimentTest*"

# k6 is not vendored (a load generator binary has no place in a Java
# source repo); download it once into a gitignored .tools/ directory,
# matching how this experiment suite was actually developed.
k6-install:
	@if [ -x "$(K6_BIN)" ]; then \
		echo "k6 already present at $(K6_BIN)"; \
	else \
		echo "Downloading k6 $(K6_VERSION)..."; \
		mkdir -p $(TOOLS_DIR); \
		curl -sL -o $(TOOLS_DIR)/k6.tar.gz "https://github.com/grafana/k6/releases/download/$(K6_VERSION)/k6-$(K6_VERSION)-linux-amd64.tar.gz"; \
		tar xzf $(TOOLS_DIR)/k6.tar.gz -C $(TOOLS_DIR); \
		rm -f $(TOOLS_DIR)/k6.tar.gz; \
		chmod +x $(K6_BIN); \
	fi

docker-up:
	docker compose up -d --build postgres redis mock-primary mock-fallback gateway
	@echo "Waiting for gateway to become healthy..."
	@for i in $$(seq 1 60); do \
		if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then echo "gateway healthy"; exit 0; fi; \
		sleep 2; \
	done; \
	echo "gateway did not become healthy in time" >&2; exit 1

docker-down:
	docker compose down

# Experiments 5-7: need the full docker compose stack, k6, and (for 6)
# a locally-run gateway-proxy.jar with JVM flags toggled - documented
# here rather than hidden, since these are genuinely heavier than a
# unit-test run (PRD's own risk callout: "make bench that only works on
# my machine" - this target is the antidote, not a shortcut around it).
# Experiment 7 alone takes ~10 minutes (100 independent breaker-reset
# cycles, by design - see run-failover-timing.py's docstring for why).
experiments-load:
	cd $(GATEWAY_DIR) && $(GRADLE) :gateway-proxy:bootJar
	K6_BIN=$(abspath $(K6_BIN)) bash $(SCRIPTS_DIR)/run-experiment-5.sh
	K6_BIN=$(abspath $(K6_BIN)) bash $(SCRIPTS_DIR)/run-experiment-6.sh
	python3 $(SCRIPTS_DIR)/run-failover-timing.py $(RESULTS_DIR)

# ---- AC1-AC9 scorecard (task 11) ----
# AC1/AC2 (latency) and AC5 (2000 concurrent streams on a constrained
# container) are measured directly here; AC3/AC4/AC6 come from the
# experiments above; AC7 is disclosed as unmeasured (needs Kubernetes,
# Phase 11/M8, not built); AC8 is Phase 06's own already-measured proof;
# AC9 (line coverage) runs JaCoCo across the core modules.
ac-scorecard:
	python3 $(SCRIPTS_DIR)/run-latency-acceptance.py $(RESULTS_DIR)
	K6_BIN=$(abspath $(K6_BIN)) bash $(SCRIPTS_DIR)/run-ac5-concurrency.sh
	cd $(GATEWAY_DIR) && $(GRADLE) :gateway-core:jacocoMergedReport :gateway-router:jacocoMergedReport :gateway-quota:jacocoMergedReport :gateway-cache:jacocoMergedReport
	python3 $(SCRIPTS_DIR)/collect-coverage.py $(RESULTS_DIR)
	python3 $(SCRIPTS_DIR)/write-ac-scorecard.py $(RESULTS_DIR)

# ---- BENCHMARKS.md assembly (task 12) ----
benchmarks-md:
	python3 $(SCRIPTS_DIR)/assemble-benchmarks-md.py
