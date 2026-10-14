import "./styles/quasar.scss";
import "@quasar/extras/material-icons/material-icons.css";
import "@quasar/extras/fontawesome-v7/fontawesome-v7.css";
import {
  Notify,
  QBtn,
  QCard,
  QCardSection,
  QForm,
  QHeader,
  QIcon,
  QInput,
  QImg,
  QLayout,
  QPage,
  QPageContainer,
  QRouteTab,
  QSelect,
  QSpace,
  QTable,
  QTabs,
  QTd,
  QTh,
  QToolbar,
  QToolbarTitle,
} from "quasar";

// Передается в app.use(Quasar, ...).
export default {
  config: {},
  components: {
    QBtn,
    QCard,
    QCardSection,
    QForm,
    QHeader,
    QIcon,
    QInput,
    QImg,
    QLayout,
    QPage,
    QPageContainer,
    QRouteTab,
    QSelect,
    QSpace,
    QTable,
    QTabs,
    QTd,
    QTh,
    QToolbar,
    QToolbarTitle,
  },
  plugins: { Notify },
};
