<template>
  <q-table
    virtual-scroll
    dense
    :rows="teams"
    :columns="columns"
    :rows-per-page-options="[0]"
    style="height: 80vh"
    row-key="name"
    no-data-label="Список команд пуст"
    table-header-class="bg-wahrwelt text-wahrwelt"
  >
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
    <template v-slot:body-cell-address="props">
      <q-td :props="props">
        <div>
          {{ props.value }}
          <q-btn
            size="sm"
            flat
            round
            icon="content_copy"
            @click="copyToClipboard(props.value)"
          />
        </div>
      </q-td>
    </template>
  </q-table>
</template>

<script>
import { mapState } from "vuex";
import { copyToClipboard } from "quasar";

export default {
  data: function () {
    return {
      columns: [
        { name: "name", label: "Название", field: "name", align: "center" },
        {
          name: "address",
          label: "Адрес",
          field: "address",
          align: "center",
        },
      ],
    };
  },
  computed: mapState(["teams"]),
  methods: { copyToClipboard },
};
</script>

<style></style>
