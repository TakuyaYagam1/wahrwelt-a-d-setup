<template>
  <q-card class="q-pa-md">
    <q-card-section>
      <div class="text-h6">Фильтр флагов</div>
    </q-card-section>
    <q-card-section>
      <q-form @submit.prevent="onSubmit">
        <div class="row">
          <div class="col col-4 q-px-sm">
            <q-select
              outlined
              v-model="sploit"
              :options="sploitOptions"
              label="Скрипт"
              ><template v-if="sploit" v-slot:append>
                <q-icon
                  name="cancel"
                  @click.stop="sploit = null"
                  class="cursor-pointer"
                /> </template
            ></q-select>
          </div>
          <div class="col col-4 q-px-sm">
            <q-select
              outlined
              v-model="team"
              :options="teamOptions"
              label="Команда"
              ><template v-if="team" v-slot:append>
                <q-icon
                  name="cancel"
                  @click.stop="team = null"
                  class="cursor-pointer"
                /> </template
            ></q-select>
          </div>
          <div class="col col-4 q-px-sm">
            <q-input
              outlined
              v-model="flag"
              label="Флаг"
              hint="Фрагмент без учета регистра"
              ><template v-if="flag" v-slot:append>
                <q-icon
                  name="cancel"
                  @click.stop="flag = null"
                  class="cursor-pointer"
                /> </template
            ></q-input>
          </div>
        </div>
        <div class="row q-pt-md">
          <div class="col col-3 q-px-sm">
            <q-input
              outlined
              v-model="since"
              label="С"
              :hint="serverTZ"
              placeholder="гггг-мм-дд чч:мм"
              ><template v-if="since" v-slot:append>
                <q-icon
                  name="cancel"
                  @click.stop="since = null"
                  class="cursor-pointer"
                /> </template
            ></q-input>
          </div>
          <div class="col col-3 q-px-sm">
            <q-input
              outlined
              v-model="until"
              label="По"
              :hint="serverTZ"
              placeholder="гггг-мм-дд чч:мм"
              ><template v-if="until" v-slot:append>
                <q-icon
                  name="cancel"
                  @click.stop="until = null"
                  class="cursor-pointer"
                /> </template
            ></q-input>
          </div>
          <div class="col col-2 q-px-sm">
            <q-select
              outlined
              v-model="status"
              :options="statusOptions"
              option-label="label"
              option-value="value"
              emit-value
              map-options
              label="Статус"
              ><template v-if="status" v-slot:append>
                <q-icon
                  name="cancel"
                  @click.stop="status = null"
                  class="cursor-pointer"
                /> </template
            ></q-select>
          </div>
          <div class="col col-4 q-px-sm">
            <q-input
              outlined
              v-model="checksystemResponse"
              label="Ответ чекера"
              hint="Фрагмент без учета регистра"
              ><template v-if="checksystemResponse" v-slot:append>
                <q-icon
                  name="cancel"
                  @click.stop="checksystemResponse = null"
                  class="cursor-pointer"
                /> </template
            ></q-input>
          </div>
        </div>
        <div class="q-pt-md">
          <q-btn label="Применить" type="submit" color="wahrwelt" />
        </div>
      </q-form>
    </q-card-section>
  </q-card>
</template>

<script>
import { mapState, mapActions, mapMutations } from "vuex";

const statusLabels = {
  QUEUED: "В очереди",
  SKIPPED: "Пропущен",
  ACCEPTED: "Принят",
  REJECTED: "Отклонен",
};

export default {
  data: function () {
    return {
      sploit: null,

      team: null,

      flag: null,
      since: null,
      until: null,

      status: null,

      checksystemResponse: null,
    };
  },
  methods: {
    onSubmit: async function () {
      const filters = {
        sploit: this.sploit,
        team: this.team,
        flag: this.flag,
        since: this.since,
        until: this.until,
        status: this.status,
        checksystem_response: this.checksystemResponse,
      };

      this.setFlagFilters(filters);
      this.setSelectedPage(1);
      await this.fetchFlags();
    },
    ...mapActions(["fetchFlags"]),
    ...mapMutations(["setSelectedPage", "setFlagFilters"]),
  },
  computed: {
    ...mapState({
      sploitOptions: "sploitFilterOptions",
      teamOptions: "teamFilterOptions",
      statusFilterOptions: "statusFilterOptions",
      serverTZ: "serverTZ",
    }),
    statusOptions() {
      return this.statusFilterOptions.map((value) => ({
        label: statusLabels[value] || value,
        value,
      }));
    },
  },
};
</script>

<style></style>
