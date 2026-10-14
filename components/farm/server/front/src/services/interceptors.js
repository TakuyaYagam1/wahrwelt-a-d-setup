import store from "@/store";
import router from "@/router";
import { Notify } from "quasar";

const passwordCheckInterceptor = function (config) {
  const password = store.state.serverPassword ?? "";
  config.headers.Authorization = password;
  config.headers["X-Token"] = password;
  return config;
};

const errorHandlerInterceptor = {
  onFulfilled: (response) => response,
  onRejected: (error) => {
    const status = error.response?.status;

    if (status === 403) {
      if (error.config?.headers?.["X-Token"] !== store.state.serverPassword) {
        return Promise.reject(error);
      }
      store.commit("setServerPassword", null);
      Notify.create({
        type: "negative",
        message: "Неверный пароль или срок сессии истек",
      });
      router.push({ name: "login" });
    } else {
      Notify.create({
        type: "negative",
        message: status ? `Ошибка API: ${status}` : "Сервер недоступен",
      });
    }

    return Promise.reject(error);
  },
};

export { passwordCheckInterceptor, errorHandlerInterceptor };
