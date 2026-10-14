<template>
  <div class="pagination">
    <span class="message">
      {{ paginationMessage }}
    </span>
    <q-btn
      v-if="pagesNumber > 2"
      icon="first_page"
      color="grey-8"
      round
      dense
      flat
      :disable="isFirstPage"
      @click="$emit('firstPage')"
    />

    <q-btn
      icon="chevron_left"
      color="grey-8"
      round
      dense
      flat
      :disable="isFirstPage"
      @click="$emit('prevPage')"
    />

    <q-btn
      icon="chevron_right"
      color="grey-8"
      round
      dense
      flat
      :disable="isLastPage"
      @click="$emit('nextPage')"
    />
    <q-btn
      v-if="pagesNumber > 2"
      icon="last_page"
      color="grey-8"
      round
      dense
      flat
      :disable="isLastPage"
      @click="$emit('lastPage')"
    />
  </div>
</template>

<script>
export default {
  name: "PaginationCustom",
  props: {
    pagesNumber: Number,
    isFirstPage: Boolean,
    isLastPage: Boolean,

    page: Number,
    pageSize: Number,
    total: Number,
  },
  computed: {
    paginationMessage: function () {
      if (!this.total) {
        return "0 из 0";
      }
      const firstRow = this.pageSize * (this.page - 1) + 1;
      const lastRow = Math.min(this.pageSize * this.page, this.total);
      return `${firstRow}-${lastRow} из ${this.total}`;
    },
  },
  emits: ["firstPage", "prevPage", "nextPage", "lastPage"],
};
</script>

<style lang="scss" scoped>
.pagination {
  font-size: 0.9em;

  > .message {
    margin-right: 1em;
  }
}
</style>
