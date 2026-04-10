import { check, sleep, group } from "k6";
import http from "k6/http";
import { BASE_URL, loginOrFail, resolveProblemId, jsonHeaders } from "./lib/common.js";

export const options = {
  scenarios: {
    sustained_load: {
      executor: "ramping-vus",
      startVUs: 10,
      stages: [
        { duration: "3m", target: 30 },
        { duration: "5m", target: 60 },
        { duration: "5m", target: 60 },
        { duration: "2m", target: 0 },
      ],
      gracefulRampDown: "30s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<1200", "p(99)<2000"],
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

function runSolo(problemIdValue) {
  const payload = JSON.stringify({
    problemId: problemIdValue,
    language: "python3",
    code: "def solve():\n    print('test')\n\nif __name__ == '__main__':\n    solve()\n",
  });
  const res = http.post(`${BASE_URL}/api/v1/solo/run`, payload, jsonHeaders());
  check(res, {
    "solo run accepted": (r) => r.status === 200,
  });
}

export default function () {
  initOncePerVu();

  group("list-and-detail", () => {
    const list = http.get(`${BASE_URL}/api/v1/problems?page=0&size=20`);
    check(list, {
      "list status 200": (r) => r.status === 200,
    });

    if (problemId) {
      const detail = http.get(`${BASE_URL}/api/v1/problems/${problemId}`);
      check(detail, {
        "detail status 200": (r) => r.status === 200,
      });
    }
  });

  group("member-api", () => {
    const me = http.get(`${BASE_URL}/api/v1/members/me`);
    check(me, {
      "me status 200": (r) => r.status === 200,
    });
  });

  if (problemId) {
    group("solo-run", () => {
      runSolo(problemId);
    });
  }

  sleep(0.5);
}
