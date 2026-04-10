import { check, sleep, group } from "k6";
import http from "k6/http";
import { BASE_URL, loginOrFail, resolveProblemId, jsonHeaders } from "./lib/common.js";

export const options = {
  scenarios: {
    spike_test: {
      executor: "ramping-vus",
      startVUs: 10,
      stages: [
        { duration: "1m", target: 10 },
        { duration: "30s", target: 150 },
        { duration: "2m", target: 150 },
        { duration: "30s", target: 10 },
        { duration: "2m", target: 10 },
      ],
      gracefulRampDown: "10s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.02"],
    http_req_duration: ["p(95)<1500", "p(99)<2500"],
  },
};

let initialized = false;
let problemId = null;

function initOncePerVu() {
  if (initialized) {
    return;
  }
  loginOrFail();
  problemId = resolveProblemId();
  initialized = true;
}

function postSoloRun(pid) {
  const payload = JSON.stringify({
    problemId: pid,
    language: "python3",
    code: "def solve():\n    print('ok')\n\nif __name__ == '__main__':\n    solve()\n",
  });
  return http.post(`${BASE_URL}/api/v1/solo/run`, payload, jsonHeaders());
}

export default function () {
  initOncePerVu();

  group("quick-read", () => {
    const res = http.get(`${BASE_URL}/api/v1/problems?page=0&size=10`);
    check(res, {
      "list status 200": (r) => r.status === 200,
    });
  });

  if (problemId) {
    group("quick-run", () => {
      const run = postSoloRun(problemId);
      check(run, {
        "solo run status 200": (r) => r.status === 200,
      });
    });
  }

  sleep(0.2);
}
