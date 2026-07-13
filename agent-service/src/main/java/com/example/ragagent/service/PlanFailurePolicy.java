package com.example.ragagent.service;

/** Deterministic outcome when a step cannot satisfy its completion predicate. */
enum PlanFailurePolicy { RETRY, SKIP, PARTIAL_FINISH, CLARIFY, FAIL }
