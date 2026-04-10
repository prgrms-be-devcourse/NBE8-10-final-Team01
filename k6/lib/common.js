import http from "k6/http";
import { check, fail } from "k6";

export const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
export const EMAIL = __ENV.K6_EMAIL || "admin@gmail.com";
export const PASSWORD = __ENV.K6_PASSWORD || "admin1234";
export const PROBLEM_ID = Number(__ENV.K6_PROBLEM_ID || 0);

export function jsonHeaders() {
  return {
    headers: {
      "Content-Type": "application/json",
    },
  };
}

export function loginOrFail() {
  const payload = JSON.stringify({
    email: EMAIL,
    password: PASSWORD,
  });

  const res = http.post(`${BASE_URL}/api/v1/members/login`, payload, jsonHeaders());
  const ok = check(res, {
    "login status is 200": (r) => r.status === 200,
  });

  if (!ok) {
    fail(`login failed: status=${res.status}, body=${res.body}`);
  }
}

export function getProblemList(page = 0, size = 20) {
  return http.get(`${BASE_URL}/api/v1/problems?page=${page}&size=${size}`);
}

export function pickProblemIdFromListResponse(res) {
  if (res.status !== 200) {
    return null;
  }
  try {
    const body = JSON.parse(res.body);
    const first = body?.problems?.[0];
    return typeof first?.problemId === "number" ? first.problemId : null;
  } catch (e) {
    return null;
  }
}

export function resolveProblemId() {
  if (PROBLEM_ID > 0) {
    return PROBLEM_ID;
  }
  const listRes = getProblemList(0, 1);
  return pickProblemIdFromListResponse(listRes);
}
