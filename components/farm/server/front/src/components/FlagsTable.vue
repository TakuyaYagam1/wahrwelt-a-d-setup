<template>
  <q-table
    :rows="flags"
    :columns="columns"
    :rows-per-page-options="[flagsPerPage]"
    v-model:pagination="pagination"
    @request="onRequest"
    row-key="flag"
    no-data-label="Флагов пока нет"
    table-header-class="bg-wahrwelt text-wahrwelt"
    dense
  >
    <template v-slot:top="scope">
      <div class="text-h6">Всего флагов: {{ scope.pagination.rowsNumber }}</div>
      <q-space />
      <pagination-custom
        :pagesNumber="scope.pagesNumber"
        :isFirstPage="scope.isFirstPage"
        :isLastPage="scope.isLastPage"
        :page="scope.pagination.page"
        :pageSize="scope.pagination.rowsPerPage"
        :total="scope.pagination.rowsNumber"
        @firstPage="scope.firstPage"
        @prevPage="scope.prevPage"
        @nextPage="scope.nextPage"
        @lastPage="scope.lastPage"
      />
      <q-btn
        flat
        round
        dense
        :icon="scope.inFullscreen ? 'fullscreen_exit' : 'fullscreen'"
        @click="scope.toggleFullscreen"
        class="q-ml-md"
      />
    </template>
    <template v-slot:no-data="{ message }">
      <div class="full-width row flex-center text-accent q-gutter-sm">
        <q-icon size="2em" name="sentiment_dissatisfied" />
        <span>{{ message }}</span>
      </div>
    </template>
    <template v-slot:header-cell="props">
      <q-th :props="props" :style="{ textAlign: 'center' }">
        {{ props.col.label }}
      </q-th>
    </template>
    <template v-slot:body-cell="props">
      <q-td :props="props">
        <div>
          {{ props.value }}
          <q-btn
            size="sm"
            flat
            round
            icon="content_copy"
            v-if="copyableColumns.includes(props.col.name)"
            @click="copyToClipboard(props.value)"
          />
        </div>
      </q-td>
    </template>
    <template v-slot:pagination="scope">
      <pagination-custom
        :pagesNumber="scope.pagesNumber"
        :isFirstPage="scope.isFirstPage"
        :isLastPage="scope.isLastPage"
        :page="scope.pagination.page"
        :pageSize="scope.pagination.rowsPerPage"
        :total="scope.pagination.rowsNumber"
        @firstPage="scope.firstPage"
        @prevPage="scope.prevPage"
        @nextPage="scope.nextPage"
        @lastPage="scope.lastPage" /></template
  ></q-table>
</template>

<script>
import { mapState, mapActions } from "vuex";
import { copyToClipboard } from "quasar";
import { flagsPerPage } from "@/config";
import PaginationCustom from "@/components/PaginationCustom.vue";

const statusLabels = {
  QUEUED: "В очереди",
  SKIPPED: "Пропущен",
  ACCEPTED: "Принят",
  REJECTED: "Отклонен",
};

export default {
  components: { PaginationCustom },
  data: function () {
    return {
      columns: [
        { name: "sploit", label: "Скрипт", field: "sploit", align: "center" },
        { name: "team", label: "Команда", field: "team", align: "center" },
        { name: "flag", label: "Флаг", field: "flag", align: "center" },
        {
          name: "time",
          label: "Время",
          field: "time",
          align: "center",
          format: (val) => new Intl.DateTimeFormat("ru-RU", {
            dateStyle: "long",
            timeStyle: "medium",
          }).format(new Date(val * 1000)),
        },
        {
          name: "status",
          label: "Статус",
          field: "status",
          align: "center",
          format: (value) => statusLabels[value] || value,
        },
        {
          name: "checksystemResponse",
          label: "Ответ чекера",
          field: "checksystemResponse",
          align: "left",
        },
      ],
      copyableColumns: ["sploit", "flag", "checksystemResponse"],
      flagsPerPage,
    };
  },
  computed: {
    pagination: {
      get: function () {
        return {
          page: this.selectedPage,
          rowsPerPage: flagsPerPage,
          rowsNumber: this.totalFlags,
        };
      },
      set: function (newPagination) {
        if (newPagination.page) {
          this.updatePage(newPagination.page);
        }
      },
    },
    ...mapState(["flags", "totalFlags", "selectedPage"]),
  },
  methods: {
    onRequest: async function (props) {
      this.pagination = props.pagination;
    },
    copyToClipboard,

    ...mapActions(["updatePage"]),
  },
};
</script>

<style></style>
