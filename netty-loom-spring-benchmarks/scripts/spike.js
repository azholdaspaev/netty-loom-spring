import { runAllEndpoints } from "./helpers.js";

export const options = {
  scenarios: {
    spike: {
      executor: "ramping-vus",
      startVUs: 10,
      stages: [
        { duration: "10s", target: 10 },
        { duration: "5s", target: 1000 },
        { duration: "30s", target: 1000 },
        { duration: "5s", target: 10 },
        { duration: "30s", target: 10 },
      ],
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<2000"],
    http_req_failed: ["rate<0.10"],
  },
};

export default function () {
  runAllEndpoints();
}
