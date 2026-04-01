import http from "k6/http";
import { check } from "k6";

export const BASE_URL = __ENV.BASE_URL || "http://localhost:8081";

export const endpoints = {
  json: "/api/benchmark/json",
  echo: "/api/benchmark/echo",
  delay: "/api/benchmark/delay",
};

export const jsonHeaders = {
  "Content-Type": "application/json",
};

export function checkResponse(res, expectedStatus) {
  check(res, {
    [`status is ${expectedStatus}`]: (r) => r.status === expectedStatus,
    "response time < 2s": (r) => r.timings.duration < 2000,
  });
}

export function runAllEndpoints() {
  const iteration = __ITER;

  switch (iteration % 3) {
    case 0: {
      const res = http.get(`${BASE_URL}${endpoints.json}`);
      checkResponse(res, 200);
      break;
    }
    case 1: {
      const payload = JSON.stringify({ data: "benchmark", count: iteration });
      const res = http.post(`${BASE_URL}${endpoints.echo}`, payload, {
        headers: jsonHeaders,
      });
      checkResponse(res, 200);
      break;
    }
    case 2: {
      const res = http.get(`${BASE_URL}${endpoints.delay}?ms=50`);
      checkResponse(res, 200);
      break;
    }
  }
}
