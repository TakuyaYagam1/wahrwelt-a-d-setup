<template>
  <q-layout view="hHh lpR fFf">
    <q-header elevated class="bg-wahrwelt text-wahrwelt">
      <q-toolbar class="bg-wahrwelt text-wahrwelt">
        <q-img class="brand-logo" src="/logo.jpg" alt="Логотип wahrwelt" ratio="1.78" />
        <q-toolbar-title> wahrwelt / Farm </q-toolbar-title>
        <q-tabs class="bg-wahrwelt text-wahrwelt" shrink>
          <q-route-tab :to="{ name: 'flags' }" label="Флаги" />
          <q-route-tab :to="{ name: 'teams' }" label="Команды" />
        </q-tabs>
        <q-space />
        <q-btn flat label="Выйти" @click="logout" />
      </q-toolbar>
    </q-header>

    <q-page-container>
      <slot />
    </q-page-container>
  </q-layout>
</template>

<script>
import { mapMutations } from "vuex";

export default {
  name: "BaseLayout",
  methods: {
    ...mapMutations(["setServerPassword"]),
    logout() {
      this.setServerPassword(null);
      this.$router.push({ name: "login" });
    },
  },
};
</script>

<style scoped>
.brand-logo {
  width: 80px;
}
</style>
