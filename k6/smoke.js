import { check, sleep, group } from "k6";
import http from "k6/http";
import { BASE_URL, loginOrFail, resolveProblemId } from "./lib/common.js";

export const options = {
  vus: 5,
  duration: "2m",
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<800"],
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

export default function () {
  initOncePerVu();

  group("public-problem-list", () => {
    const res = http.get(`${BASE_URL}/api/v1/problems?page=0&size=20`);
    check(res, {
      "problem list status 200": (r) => r.status === 200,
    });
  });

  if (problemId) {
    group("public-problem-detail", () => {
      const res = http.get(`${BASE_URL}/api/v1/problems/${problemId}?lang=ko`);
      check(res, {
        "problem detail status 200": (r) => r.status === 200,
      });
    });
  }

  group("auth-profile", () => {
    const me = http.get(`${BASE_URL}/api/v1/members/me`);
    check(me, {
      "me status 200": (r) => r.status === 200,
    });

    const progress = http.get(`${BASE_URL}/api/v1/members/me/rating-progress`);
    check(progress, {
      "rating-progress status 200": (r) => r.status === 200,
    });
  });

  sleep(1);
}
