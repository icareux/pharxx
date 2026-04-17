package com.pharmasystem.ui;

import javafx.scene.input.KeyEvent;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.*;
import javafx.scene.Node;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import com.pharmasystem.database.MedicineDAO;
import com.pharmasystem.database.BatchDAO;
import com.pharmasystem.session.Session;
import com.pharmasystem.model.*;

import javafx.event.ActionEvent;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class InventoryController {

    @FXML private TableView<Medicine>                    table;
    @FXML private TableColumn<Medicine, Boolean>         selectCol;
    @FXML private TableColumn<Medicine, String>          nameCol;
    @FXML private TableColumn<Medicine, Integer>         stockCol;
    @FXML private TableColumn<Medicine, Double>          priceCol;
    @FXML private TableColumn<Medicine, String>          expiryCol;
    @FXML private TableColumn<Medicine, String>          rxCol;

    @FXML private Button    updateBtn;
    @FXML private Button    addMedicineBtn;
    @FXML private Button    adjustPriceBtn;
    @FXML private Button    deleteMedicineBtn;
    @FXML private Button    removeExpiredBtn;
    @FXML private TextField inventorySearch;

    private final MedicineDAO medicineDAO = new MedicineDAO();
    private final BatchDAO    batchDAO    = new BatchDAO();

    // ── Checkbox state — one property per medicine id ─────────
    // ObservableMap so changes propagate without manual refresh
    private final Map<Integer, SimpleBooleanProperty> checkedMap = new HashMap<>();

    // ── Undo / Redo stacks ────────────────────────────────────
    // Each entry is a Runnable that re-applies the inverse of the last action.
    private final Deque<UndoEntry> undoStack = new ArrayDeque<>();
    private final Deque<UndoEntry> redoStack = new ArrayDeque<>();

    /** An undoable action: forward = redo the action, backward = undo it. */
    private record UndoEntry(String description, Runnable backward, Runnable forward) {}

    // ── Init ──────────────────────────────────────────────────

    @FXML
    public void initialize() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        // Allow row selection AND checkbox selection independently
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setEditable(true);

        setupColumns();
        applyRoleRestrictions();
        loadData();
    }

    // ── Column setup ──────────────────────────────────────────

    private void setupColumns() {

        // ── CHECKBOX column ────────────────────────────────────
        // Key fix: CheckBoxTableCell needs the column to be editable AND
        // the cell value factory must return the SAME property instance each
        // time so toggling actually mutates it.
        selectCol.setEditable(true);
        selectCol.setSortable(false);
        selectCol.setResizable(false);

        selectCol.setCellValueFactory(data -> {
            int id = data.getValue().getMedicineId();
            // putIfAbsent guarantees we always return the same property for this id
            checkedMap.putIfAbsent(id, new SimpleBooleanProperty(false));
            return checkedMap.get(id);
        });

        // CheckBoxTableCell reads the ObservableValue from the value factory
        // and automatically toggles it when clicked — no extra listener needed.
        selectCol.setCellFactory(CheckBoxTableCell.forTableColumn(selectCol));

        // ── NAME ───────────────────────────────────────────────
        nameCol.setCellValueFactory(data ->
            new javafx.beans.property.SimpleStringProperty(data.getValue().getName()));

        // ── STOCK ──────────────────────────────────────────────
        stockCol.setCellValueFactory(data ->
            new javafx.beans.property.SimpleIntegerProperty(
                batchDAO.getTotalStockByMedicineId(data.getValue().getMedicineId())
            ).asObject());
        stockCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Integer v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setStyle(""); return; }
                setText(String.valueOf(v));
                if (v == 0)       setStyle("-fx-text-fill: #DC2626; -fx-font-weight: bold;");
                else if (v <= 10) setStyle("-fx-text-fill: #D97706; -fx-font-weight: bold;");
                else              setStyle("-fx-text-fill: #16A34A; -fx-font-weight: bold;");
            }
        });

        // ── PRICE ──────────────────────────────────────────────
        priceCol.setCellValueFactory(data ->
            new javafx.beans.property.SimpleDoubleProperty(data.getValue().getPrice()).asObject());
        priceCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : String.format("₱%.2f", v));
            }
        });

        // ── EXPIRY (earliest active batch) ─────────────────────
        expiryCol.setCellValueFactory(data -> {
            List<Batch> batches = batchDAO.getBatchesByMedicine(data.getValue().getMedicineId());
            if (batches == null || batches.isEmpty())
                return new javafx.beans.property.SimpleStringProperty("—");
            String earliest = batches.stream()
                .filter(b -> b.getExpiryDate() != null)
                .map(b -> b.getExpiryDate().toString())
                .sorted().findFirst().orElse("—");
            return new javafx.beans.property.SimpleStringProperty(earliest);
        });
        expiryCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setStyle(""); return; }
                setText(v);
                try {
                    setStyle(!"—".equals(v) && LocalDate.parse(v).isBefore(LocalDate.now())
                        ? "-fx-text-fill: #DC2626; -fx-font-weight: bold;" : "");
                } catch (Exception e) { setStyle(""); }
            }
        });

        // ── PRESCRIPTION ───────────────────────────────────────
        rxCol.setCellValueFactory(data ->
            new javafx.beans.property.SimpleStringProperty(
                data.getValue().requiresPrescription() ? "Yes" : "No"));
        rxCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setStyle(""); return; }
                setText(v);
                setStyle("Yes".equals(v)
                    ? "-fx-text-fill: #D97706; -fx-font-weight: bold;"
                    : "-fx-text-fill: #64748B;");
            }
        });
    }

    private void applyRoleRestrictions() {
        boolean isManager = Session.currentUser instanceof Manager;
        updateBtn.setDisable(!isManager);
        addMedicineBtn.setDisable(!isManager);
        adjustPriceBtn.setDisable(!isManager);
        if (deleteMedicineBtn != null) deleteMedicineBtn.setDisable(!isManager);
        if (removeExpiredBtn  != null) removeExpiredBtn.setDisable(!isManager);
    }

    // ── Data ──────────────────────────────────────────────────

    private void loadData() {
        checkedMap.clear();
        table.setItems(FXCollections.observableArrayList(medicineDAO.getAllMedicines()));
    }

    @FXML
    private void onInventorySearch(KeyEvent event) {
        String keyword = inventorySearch.getText();
        if (keyword == null || keyword.trim().isEmpty()) { loadData(); return; }
        String lower = keyword.toLowerCase();
        table.setItems(FXCollections.observableArrayList(
            medicineDAO.getAllMedicines().stream()
                .filter(m -> m.getName().toLowerCase().contains(lower))
                .toList()
        ));
    }

    /** Returns all checked medicines; falls back to row-selection if none checked. */
    private List<Medicine> getCheckedOrSelected() {
        List<Medicine> checked = table.getItems().stream()
            .filter(m -> {
                SimpleBooleanProperty p = checkedMap.get(m.getMedicineId());
                return p != null && p.get();
            })
            .collect(Collectors.toList());
        if (!checked.isEmpty()) return checked;
        Medicine sel = table.getSelectionModel().getSelectedItem();
        return sel != null ? List.of(sel) : Collections.emptyList();
    }

    // ── Undo / Redo ───────────────────────────────────────────

    /** Records an undoable step. Forward is redo, backward is undo. */
    private void pushUndo(String description, Runnable backward, Runnable forward) {
        undoStack.push(new UndoEntry(description, backward, forward));
        redoStack.clear(); // new action clears redo history
    }

    @FXML
    private void handleUndo() {
        if (undoStack.isEmpty()) {
            UIUtils.showAlert(Alert.AlertType.INFORMATION, "Nothing to Undo", "No recent actions to undo.");
            return;
        }
        UndoEntry entry = undoStack.pop();
        entry.backward().run();
        redoStack.push(entry);
        loadData();
        UIUtils.showAlert(Alert.AlertType.INFORMATION, "Undone", "Undid: " + entry.description());
    }

    @FXML
    private void handleRedo() {
        if (redoStack.isEmpty()) {
            UIUtils.showAlert(Alert.AlertType.INFORMATION, "Nothing to Redo", "No actions to redo.");
            return;
        }
        UndoEntry entry = redoStack.pop();
        entry.forward().run();
        undoStack.push(entry); // redone action is now undoable again
        loadData();
        UIUtils.showAlert(Alert.AlertType.INFORMATION, "Redone", "Redid: " + entry.description());
    }

    // ── Manage Stock ──────────────────────────────────────────

    @FXML
    private void handleAddButton() {
        if (!(Session.currentUser instanceof Manager)) {
            UIUtils.showAlert(Alert.AlertType.ERROR, "Access Denied", "Only managers can manage stock.");
            return;
        }
        List<Medicine> targets = getCheckedOrSelected();
        if (targets.isEmpty()) {
            UIUtils.showAlert(Alert.AlertType.ERROR, "No Selection", "Check or select a medicine row first.");
            return;
        }
        openManageStockPopup(targets.get(0));
    }

    private void openManageStockPopup(Medicine med) {
        Stage popup = new Stage();
        popup.initModality(Modality.APPLICATION_MODAL);
        popup.initOwner(table.getScene().getWindow());
        popup.setTitle("Manage Stock — " + med.getName());
        popup.setResizable(false);

        int currentStock = batchDAO.getTotalStockByMedicineId(med.getMedicineId());

        // Header
        HBox header = popupHeader("Manage Stock", med.getName() + " — " + currentStock + " units in stock");

        // ADD tab
        Label addQtyLabel = fieldLabel("QUANTITY TO ADD");
        TextField addQty  = styledTextField("e.g. 50");
        Label addExpLabel = fieldLabel("EXPIRY DATE");
        DatePicker addExp = styledDatePicker();
        Label addError    = errorLabel();
        Button addBtn     = primaryButton("Add Batch");

        VBox addTab = sectionBox(
            fieldGroup(addQtyLabel, addQty),
            fieldGroup(addExpLabel, addExp),
            addError, addBtn);

        addBtn.setOnAction(e -> {
            addError.setText("");
            int qty;
            try {
                qty = Integer.parseInt(addQty.getText().trim());
                if (qty <= 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) { addError.setText("Enter a positive whole number."); return; }
            if (addExp.getValue() == null)           { addError.setText("Select an expiry date."); return; }
            if (addExp.getValue().isBefore(LocalDate.now())) { addError.setText("Expiry date cannot be in the past."); return; }
            try {
                int finalQty = qty;
                LocalDate expiry = addExp.getValue();
                batchDAO.addBatch(med.getMedicineId(), finalQty, expiry, LocalDate.now());
                // Undo: deduct the quantity we just added (FIFO removes what we added last if newest)
                pushUndo("Add " + finalQty + " units to " + med.getName(),
                    () -> batchDAO.deductStockFIFO(med.getMedicineId(), finalQty),
                    () -> batchDAO.addBatch(med.getMedicineId(), finalQty, expiry, LocalDate.now()));
                popup.close();
                loadData();
                UIUtils.showAlert(Alert.AlertType.INFORMATION, "Stock Added",
                    finalQty + " units added to " + med.getName() + ".");
            } catch (Exception ex) {
                addError.setText(ex.getMessage() != null ? ex.getMessage() : "Failed to add stock.");
            }
        });

        // REDUCE tab
        Label redLabel    = fieldLabel("QUANTITY TO REMOVE (FIFO)");
        TextField redQty  = styledTextField("e.g. 10");
        Label redError    = errorLabel();
        Button redBtn     = dangerButton("Remove Stock");

        VBox reduceTab = sectionBox(
            fieldGroup(redLabel, redQty),
            infoBox("Stock is deducted oldest-batch-first (FIFO). Use 🗑 Remove Expired for expired batches."),
            redError, redBtn);

        redBtn.setOnAction(e -> {
            redError.setText("");
            int qty;
            try {
                qty = Integer.parseInt(redQty.getText().trim());
                if (qty <= 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) { redError.setText("Enter a positive whole number."); return; }
            if (qty > currentStock) { redError.setText("Cannot exceed current stock (" + currentStock + ")."); return; }
            Alert c = new Alert(Alert.AlertType.CONFIRMATION);
            c.setTitle("Confirm Reduction");
            c.setHeaderText("Remove " + qty + " units from " + med.getName() + "?");
            c.setContentText("Deducted FIFO. This step can be undone via the Undo button.");
            c.showAndWait().ifPresent(r -> {
                if (r == ButtonType.OK) {
                    int finalQty = qty;
                    batchDAO.deductStockFIFO(med.getMedicineId(), finalQty);
                    pushUndo("Remove " + finalQty + " units from " + med.getName(),
                        () -> batchDAO.addBatch(med.getMedicineId(), finalQty,
                            LocalDate.now().plusYears(1), LocalDate.now()),
                        () -> batchDAO.deductStockFIFO(med.getMedicineId(), finalQty));
                    popup.close();
                    loadData();
                    UIUtils.showAlert(Alert.AlertType.INFORMATION, "Stock Reduced",
                        finalQty + " units removed from " + med.getName() + ".");
                }
            });
        });

        // Tab bar
        Button tabAdd    = new Button("＋  Add Stock");
        Button tabReduce = new Button("－  Reduce Stock");
        styleTab(tabAdd, true);
        styleTab(tabReduce, false);
        HBox tabBar = tabBar(tabAdd, tabReduce);
        VBox tabContent = new VBox(addTab);
        tabAdd.setOnAction(e    -> { styleTab(tabAdd, true);  styleTab(tabReduce, false); tabContent.getChildren().setAll(addTab); });
        tabReduce.setOnAction(e -> { styleTab(tabAdd, false); styleTab(tabReduce, true);  tabContent.getChildren().setAll(reduceTab); });

        VBox root = new VBox(0, header, tabBar, tabContent);
        root.setStyle("-fx-background-color: #F4F6FA;");
        popup.setScene(new javafx.scene.Scene(root, 400, 380));
        popup.showAndWait();
    }

    // ── Add Medicine ──────────────────────────────────────────

    @FXML
    private void openAddMedicine() {
        if (!(Session.currentUser instanceof Manager)) {
            UIUtils.showAlert(Alert.AlertType.ERROR, "Access Denied", "Only managers can add medicines.");
            return;
        }
        try {
            javafx.fxml.FXMLLoader loader = UIUtils.createLoader("/com/pharmasystem/ui/AddMedicine.fxml");
            Stage stage = new Stage();
            stage.setScene(new javafx.scene.Scene(loader.load()));
            stage.setTitle("Add Medicine");
            stage.initOwner(table.getScene().getWindow());
            stage.showAndWait();
            loadData();
        } catch (Exception e) {
            e.printStackTrace();
            UIUtils.showAlert(Alert.AlertType.ERROR, "Open Failed", "Unable to open Add Medicine.");
        }
    }

    // ── Delete Medicine ───────────────────────────────────────

    @FXML
    private void handleDeleteMedicine() {
        if (!(Session.currentUser instanceof Manager)) {
            UIUtils.showAlert(Alert.AlertType.ERROR, "Access Denied", "Only managers can delete medicines.");
            return;
        }
        List<Medicine> targets = getCheckedOrSelected();
        if (targets.isEmpty()) {
            UIUtils.showAlert(Alert.AlertType.ERROR, "No Selection", "Check or select medicines first.");
            return;
        }

        // BULK DELETE — more than one medicine checked
        if (targets.size() > 1) {
            openBulkDeletePopup(targets);
            return;
        }

        // SINGLE DELETE — show the full option dialog
        openDeletePopup(targets.get(0));
    }

    /** Bulk-delete confirmation for multiple checked medicines. */
    private void openBulkDeletePopup(List<Medicine> targets) {
        Stage popup = new Stage();
        popup.initModality(Modality.APPLICATION_MODAL);
        popup.initOwner(table.getScene().getWindow());
        popup.setTitle("Bulk Delete — " + targets.size() + " medicines");
        popup.setResizable(false);

        HBox header = popupHeader("⚠  Bulk Delete",
            targets.size() + " medicines selected for deletion");
        header.setStyle("-fx-background-color: #7F1D1D; -fx-padding: 18 24 18 24;");

        StringBuilder sb = new StringBuilder("The following will be permanently deleted:\n\n");
        targets.forEach(m -> sb.append("  •  ").append(m.getName())
            .append("  (").append(batchDAO.getTotalStockByMedicineId(m.getMedicineId())).append(" units)\n"));
        sb.append("\nAll batches for each medicine will also be removed.");

        Label warn = new Label(sb.toString());
        warn.setWrapText(true);
        warn.setStyle("-fx-text-fill: #DC2626; -fx-font-size: 12px;");

        Button deleteAll = dangerButton("Delete All " + targets.size() + " Medicines");
        deleteAll.setStyle("-fx-background-color: #DC2626; -fx-text-fill: white; "
            + "-fx-font-weight: bold; -fx-font-size: 13px;");
        Button cancel = secondaryButton("Cancel");
        cancel.setOnAction(e -> popup.close());

        deleteAll.setOnAction(e -> {
            List<Integer> ids = targets.stream()
                .map(Medicine::getMedicineId).collect(Collectors.toList());
            List<String> names = targets.stream()
                .map(Medicine::getName).collect(Collectors.toList());

            // Execute bulk deletion
            ids.forEach(medicineDAO::deleteMedicine);

            // Register undo — re-creating full medicine records is complex;
            // undo here just notifies the user that it cannot be reversed automatically.
            pushUndo("Bulk delete " + targets.size() + " medicines",
                () -> { /* Bulk hard-delete cannot be automatically reversed */ },
                () -> ids.forEach(medicineDAO::deleteMedicine));

            popup.close();
            loadData();
            UIUtils.showAlert(Alert.AlertType.INFORMATION, "Deleted",
                targets.size() + " medicines removed: " + String.join(", ", names) + ".");
        });

        VBox body = sectionBox(warn, deleteAll, cancel);
        VBox root = new VBox(0, header, body);
        root.setStyle("-fx-background-color: #F4F6FA;");
        popup.setScene(new javafx.scene.Scene(root, 430, 320));
        popup.showAndWait();
    }

    /**
     * Single-medicine delete popup with three independent options:
     *   A – delete one or more selected batches
     *   B – remove a custom quantity (FIFO)
     *   C – delete the entire medicine record
     */
    private void openDeletePopup(Medicine med) {
        Stage popup = new Stage();
        popup.initModality(Modality.APPLICATION_MODAL);
        popup.initOwner(table.getScene().getWindow());
        popup.setTitle("Delete / Reduce — " + med.getName());
        popup.setResizable(false);

        List<Batch> batches = batchDAO.getBatchesByMedicine(med.getMedicineId());
        int totalStock      = batchDAO.getTotalStockByMedicineId(med.getMedicineId());

        HBox header = popupHeader("⚠  Delete / Remove Stock",
            med.getName() + " — " + totalStock + " units in stock");
        header.setStyle("-fx-background-color: #7F1D1D; -fx-padding: 18 24 18 24;");

        // ── OPTION A: multi-select batch delete ────────────────
        Label batchSectionLabel = fieldLabel("DELETE SPECIFIC BATCHES (MULTI-SELECT)");

        // ListView with checkboxes for each batch
        ObservableList<Batch> batchObsList =
            FXCollections.observableArrayList(batches);
        Map<Integer, SimpleBooleanProperty> batchChecked = new LinkedHashMap<>();
        for (Batch b : batches) {
            batchChecked.put(b.getBatchId(), new SimpleBooleanProperty(false));
        }

        ListView<Batch> batchListView = new ListView<>(batchObsList);
        batchListView.setPrefHeight(Math.min(150, batches.size() * 44 + 10));
        batchListView.setCellFactory(lv -> new ListCell<>() {
            private final CheckBox cb  = new CheckBox();
            private final Label    lbl = new Label();
            private final HBox     row = new HBox(10, cb, lbl);
            { row.setAlignment(Pos.CENTER_LEFT); }
            @Override protected void updateItem(Batch b, boolean empty) {
                super.updateItem(b, empty);
                if (empty || b == null) { setGraphic(null); return; }
                lbl.setText("Batch #" + b.getBatchId()
                    + "   Qty: " + b.getQuantity()
                    + "   Expiry: " + b.getExpiryDate());
                lbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #334155;");
                // Bind checkbox to our map
                cb.selectedProperty().unbindBidirectional(
                    batchChecked.get(b.getBatchId()));
                cb.setSelected(batchChecked.get(b.getBatchId()).get());
                cb.selectedProperty().addListener((obs, o, n) ->
                    batchChecked.get(b.getBatchId()).set(n));
                setGraphic(row);
            }
        });

        Label batchError  = errorLabel();
        Button batchDelBtn = dangerButton("Delete Selected Batches");

        batchDelBtn.setOnAction(e -> {
            batchError.setText("");
            List<Integer> toDelete = batchChecked.entrySet().stream()
                .filter(en -> en.getValue().get())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

            if (toDelete.isEmpty()) { batchError.setText("Select at least one batch."); return; }

            int totalUnits = batches.stream()
                .filter(b -> toDelete.contains(b.getBatchId()))
                .mapToInt(Batch::getQuantity).sum();

            Alert c = new Alert(Alert.AlertType.CONFIRMATION);
            c.setTitle("Confirm Batch Deletion");
            c.setHeaderText("Delete " + toDelete.size() + " batch(es)?");
            c.setContentText(totalUnits + " units will be permanently removed.");
            c.showAndWait().ifPresent(r -> {
                if (r == ButtonType.OK) {
                    // Snapshot batch data for undo before deleting
                    List<Batch> deleted = batches.stream()
                        .filter(b -> toDelete.contains(b.getBatchId()))
                        .collect(Collectors.toList());

                    batchDAO.deleteBatchesByIds(toDelete);

                    pushUndo("Delete " + deleted.size() + " batch(es) from " + med.getName(),
                        // Undo: re-add the deleted batches
                        () -> deleted.forEach(b -> batchDAO.addBatch(
                            b.getMedicineId(),
                            b.getQuantity(),
                            b.getExpiryDate(),   // LocalDate expiry
                            b.getPurchaseDate()  // LocalDate purchase
                        )),
                        // Redo: delete again, matching by properties instead of stale IDs
                        () -> {
                            List<Batch> current = batchDAO.getBatchesByMedicine(med.getMedicineId());
                            List<Integer> idsToDelete = current.stream()
                                .filter(b -> deleted.stream().anyMatch(orig ->
                                    orig.getQuantity() == b.getQuantity() &&
                                    Objects.equals(orig.getExpiryDate(), b.getExpiryDate()) &&
                                    Objects.equals(orig.getPurchaseDate(), b.getPurchaseDate())))
                                .map(Batch::getBatchId)
                                .toList();
                            batchDAO.deleteBatchesByIds(idsToDelete);
                        });



                    popup.close();
                    loadData();
                    UIUtils.showAlert(Alert.AlertType.INFORMATION, "Batches Deleted",
                        toDelete.size() + " batch(es) removed (" + totalUnits + " units).");
                }
            });
        });

        VBox batchSection = sectionBox(batchSectionLabel, batchListView, batchError, batchDelBtn);

        // ── OPTION B: quantity (FIFO) ──────────────────────────
        Label qtyLabel    = fieldLabel("REMOVE A SPECIFIC QUANTITY (FIFO)");
        TextField qtyField = styledTextField("e.g. 20");
        Label qtyError    = errorLabel();
        Button qtyDelBtn  = dangerButton("Remove Quantity");

        qtyDelBtn.setOnAction(e -> {
            qtyError.setText("");
            int qty;
            try {
                qty = Integer.parseInt(qtyField.getText().trim());
                if (qty <= 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) { qtyError.setText("Enter a positive whole number."); return; }
            if (qty > totalStock) { qtyError.setText("Cannot exceed total stock (" + totalStock + ")."); return; }

            Alert c = new Alert(Alert.AlertType.CONFIRMATION);
            c.setTitle("Confirm Removal");
            c.setHeaderText("Remove " + qty + " units from " + med.getName() + "?");
            c.setContentText("Oldest batches removed first (FIFO). Can be undone.");
            c.showAndWait().ifPresent(r -> {
                if (r == ButtonType.OK) {
                    int finalQty = qty;
                    batchDAO.deductStockFIFO(med.getMedicineId(), finalQty);
                    pushUndo("Remove " + finalQty + " units from " + med.getName(),
                        () -> batchDAO.addBatch(med.getMedicineId(), finalQty,
                            LocalDate.now().plusYears(1), LocalDate.now()),
                        () -> batchDAO.deductStockFIFO(med.getMedicineId(), finalQty));
                    popup.close();
                    loadData();
                    UIUtils.showAlert(Alert.AlertType.INFORMATION, "Done",
                        finalQty + " units removed from " + med.getName() + ".");
                }
            });
        });

        VBox qtySection = sectionBox(fieldGroup(qtyLabel, qtyField), qtyError, qtyDelBtn);

        // ── OPTION C: delete entire record ─────────────────────
        Label wholeLabel = fieldLabel("DELETE ENTIRE MEDICINE RECORD");
        Label wholeWarn  = new Label("⚠  Permanently removes \"" + med.getName()
            + "\" and ALL its batches. This cannot be undone.");
        wholeWarn.setWrapText(true);
        wholeWarn.setStyle("-fx-text-fill: #DC2626; -fx-font-size: 12px;");
        Button wholeDelBtn = dangerButton("Delete Entire Record");
        wholeDelBtn.setStyle("-fx-background-color: #DC2626; -fx-text-fill: white;");
        wholeDelBtn.setOnAction(e -> {
            Alert c = new Alert(Alert.AlertType.CONFIRMATION);
            c.setTitle("Confirm Full Deletion");
            c.setHeaderText("Delete \"" + med.getName() + "\" entirely?");
            c.setContentText("ALL batches and the medicine record will be permanently deleted.\n\nThis cannot be undone.");
            c.showAndWait().ifPresent(r -> {
                if (r == ButtonType.OK) {
                    boolean deleted = medicineDAO.deleteMedicine(med.getMedicineId());
                    popup.close();
                    loadData();
                    if (deleted)
                        UIUtils.showAlert(Alert.AlertType.INFORMATION, "Deleted",
                            "\"" + med.getName() + "\" permanently removed.");
                    else
                        UIUtils.showAlert(Alert.AlertType.ERROR, "Failed",
                            "Unable to delete. Please try again.");
                }
            });
        });

        VBox wholeSection = sectionBox(wholeLabel, wholeWarn, wholeDelBtn);

        // Dividers
        Region sep1 = separator();
        Region sep2 = separator();

        VBox body = new VBox(0, batchSection, sep1, qtySection, sep2, wholeSection);
        body.setStyle("-fx-background-color: #F4F6FA;");

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-width: 0;");

        VBox root = new VBox(0, header, scroll);
        root.setStyle("-fx-background-color: #F4F6FA;");
        popup.setScene(new javafx.scene.Scene(root, 450, 600));
        popup.showAndWait();
    }

    // ── Remove Expired ─────────────────────────────────────────

    @FXML
    private void handleRemoveExpired() {
        if (!(Session.currentUser instanceof Manager)) {
            UIUtils.showAlert(Alert.AlertType.ERROR, "Access Denied", "Only managers can remove expired medicines.");
            return;
        }
        LocalDate today = LocalDate.now();
        List<Medicine> expired = medicineDAO.getAllMedicines().stream()
            .filter(m -> {
                List<Batch> b = batchDAO.getBatchesByMedicine(m.getMedicineId());
                return b != null && b.stream()
                    .anyMatch(x -> x.getExpiryDate() != null && x.getExpiryDate().isBefore(today));
            })
            .collect(Collectors.toList());

        if (expired.isEmpty()) {
            UIUtils.showAlert(Alert.AlertType.INFORMATION, "No Expired Medicines",
                "There are no expired medicines in inventory.");
            return;
        }

        StringBuilder sb = new StringBuilder("The following medicines have expired batches:\n\n");
        expired.forEach(m -> sb.append("  •  ").append(m.getName()).append("\n"));
        sb.append("\nConfirm to remove all expired batches.");

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove Expired Batches");
        confirm.setHeaderText("Remove expired inventory?");
        confirm.setContentText(sb.toString());
        confirm.getDialogPane().setPrefWidth(460);
        confirm.showAndWait().ifPresent(resp -> {
            if (resp == ButtonType.OK) {
                int removed = batchDAO.removeExpiredBatches();
                loadData();
                UIUtils.showAlert(Alert.AlertType.INFORMATION, "Done",
                    removed + " expired batch(es) removed from inventory.");
            }
        });
    }

    // ── Adjust Prices ─────────────────────────────────────────

    @FXML
    private void openAdjustPrices() {
        if (!(Session.currentUser instanceof Manager)) {
            UIUtils.showAlert(Alert.AlertType.ERROR, "Access Denied", "Only managers can adjust prices.");
            return;
        }
        List<Medicine> targets = getCheckedOrSelected();
        if (targets.isEmpty()) {
            UIUtils.showAlert(Alert.AlertType.ERROR, "No Selection", "Check or select a medicine first.");
            return;
        }
        Medicine selected = targets.get(0);

        Stage popup = new Stage();
        popup.initModality(Modality.APPLICATION_MODAL);
        popup.initOwner(table.getScene().getWindow());
        popup.setTitle("Adjust Price — " + selected.getName());
        popup.setResizable(false);

        HBox header = popupHeader("Adjust Price", selected.getName());
        Label currentLabel = new Label(String.format("Current price: ₱%.2f", selected.getPrice()));
        currentLabel.setStyle("-fx-text-fill: #64748B; -fx-font-size: 12px;");
        TextField priceField = styledTextField("New price…");
        Label errorLbl = errorLabel();
        Button saveBtn = primaryButton("Save Price");

        saveBtn.setOnAction(e -> {
            try {
                double newPrice = Double.parseDouble(priceField.getText().trim());
                double oldPrice = selected.getPrice();
                if (newPrice < 0) { errorLbl.setText("Price cannot be negative."); return; }
                if (!medicineDAO.updatePrice(selected.getMedicineId(), newPrice)) {
                    errorLbl.setText("Update failed."); return;
                }
                pushUndo("Price change for " + selected.getName(),
                    () -> medicineDAO.updatePrice(selected.getMedicineId(), oldPrice),
                    () -> medicineDAO.updatePrice(selected.getMedicineId(), newPrice));
                popup.close();
                loadData();
            } catch (NumberFormatException ex) {
                errorLbl.setText("Enter a valid numeric price.");
            }
        });

        VBox body = sectionBox(currentLabel, fieldGroup(fieldLabel("NEW PRICE (₱)"), priceField), errorLbl, saveBtn);
        VBox root = new VBox(0, header, body);
        root.setStyle("-fx-background-color: #F4F6FA;");
        popup.setScene(new javafx.scene.Scene(root, 360, 270));
        popup.showAndWait();
    }

    // ── View Batches (with per-row delete) ────────────────────

    @FXML
    private void openViewBatches() {
        List<Medicine> targets = getCheckedOrSelected();
        if (targets.isEmpty()) {
            UIUtils.showAlert(Alert.AlertType.ERROR, "No Selection", "Check or select a medicine first.");
            return;
        }
        Medicine selected = targets.get(0);

        // We use an ObservableList so the ListView updates immediately after a delete
        ObservableList<Batch> batchList =
            FXCollections.observableArrayList(batchDAO.getBatchesByMedicine(selected.getMedicineId()));

        // ── Batch list view with inline Delete button ──────────
        ListView<Batch> batchListView = new ListView<>(batchList);
        batchListView.getStyleClass().add("search-list");
        batchListView.setStyle("-fx-background-color: transparent; -fx-border-width: 0;");
        VBox.setVgrow(batchListView, Priority.ALWAYS);

        batchListView.setCellFactory(lv -> new ListCell<>() {
            private final Label   idLbl      = new Label();
            private final Label   qtyLbl     = new Label();
            private final Label   expiryLbl  = new Label();
            private final Label   purchaseLbl = new Label();
            private final Button  deleteBtn  = new Button("Delete");
            private final Region  spacer     = new Region();
            private final HBox    row        = new HBox(12, idLbl, qtyLbl, expiryLbl, purchaseLbl, spacer, deleteBtn);

            {
                HBox.setHgrow(spacer, Priority.ALWAYS);
                deleteBtn.getStyleClass().add("btn-remove");
                deleteBtn.setStyle("-fx-background-color: #FEF2F2; -fx-text-fill: #DC2626; "
                    + "-fx-font-size: 11px; -fx-font-weight: bold; -fx-background-radius: 5;");
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new javafx.geometry.Insets(6, 8, 6, 8));

                deleteBtn.setOnAction(e -> {
                    Batch b = getItem();
                    if (b == null) return;
                    Alert c = new Alert(Alert.AlertType.CONFIRMATION);
                    c.setTitle("Delete Batch");
                    c.setHeaderText("Delete Batch #" + b.getBatchId() + "?");
                    c.setContentText(b.getQuantity() + " units (expiry " + b.getExpiryDate()
                        + ") will be removed. This step can be undone.");
                    c.showAndWait().ifPresent(r -> {
                        if (r == ButtonType.OK) {
                            batchDAO.deleteBatchById(b.getBatchId());
                            pushUndo("Delete Batch #" + b.getBatchId(),
                                () -> batchDAO.addBatch(b.getMedicineId(), b.getQuantity(),
                                    b.getExpiryDate(), b.getPurchaseDate()),
                                () -> batchDAO.deleteBatchById(b.getBatchId()));
                            batchList.remove(b);
                            loadData(); // refresh main table stock counts
                        }
                    });
                });
            }

            @Override protected void updateItem(Batch b, boolean empty) {
                super.updateItem(b, empty);
                if (empty || b == null) { setGraphic(null); return; }

                String base = "-fx-font-size: 12px; ";
                idLbl.setText("Batch #" + b.getBatchId());
                idLbl.setStyle(base + "-fx-text-fill: #64748B;");
                qtyLbl.setText("Qty: " + b.getQuantity());
                qtyLbl.setStyle(base + "-fx-font-weight: bold; -fx-text-fill: #0A1628;");

                boolean expired = b.getExpiryDate() != null && b.getExpiryDate().isBefore(LocalDate.now());
                expiryLbl.setText("Exp: " + (b.getExpiryDate() != null ? b.getExpiryDate() : "—"));
                expiryLbl.setStyle(base + (expired
                    ? "-fx-text-fill: #DC2626; -fx-font-weight: bold;"
                    : "-fx-text-fill: #334155;"));

                purchaseLbl.setText("Pur: " + (b.getPurchaseDate() != null ? b.getPurchaseDate() : "—"));
                purchaseLbl.setStyle(base + "-fx-text-fill: #94A3B8;");

                setGraphic(row);
            }
        });

        // Header
        HBox header = popupHeader("Batch Details", selected.getName());

        // Summary bar
        int total = batchList.stream().mapToInt(Batch::getQuantity).sum();
        Label summaryLbl = new Label(batchList.size() + " batches   ·   " + total + " total units");
        summaryLbl.setStyle("-fx-text-fill: #64748B; -fx-font-size: 12px;");

        Button closeBtn = primaryButton("Close");
        closeBtn.setOnAction(e -> ((Stage) closeBtn.getScene().getWindow()).close());
        HBox actions = new HBox(closeBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setStyle("-fx-padding: 0 20 20 20;");

        VBox card = new VBox(0, batchListView);
        card.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 10; "
            + "-fx-border-color: #E2E8F0; -fx-border-width: 1; -fx-border-radius: 10;");
        VBox.setVgrow(card, Priority.ALWAYS);

        VBox body = new VBox(10, summaryLbl, card, actions);
        body.setStyle("-fx-padding: 18 20 0 20;");
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox root = new VBox(0, header, body);
        root.setStyle("-fx-background-color: #F4F6FA;");
        VBox.setVgrow(body, Priority.ALWAYS);

        Stage popup = new Stage();
        popup.initModality(Modality.APPLICATION_MODAL);
        popup.initOwner(table.getScene().getWindow());
        popup.setTitle("Batches — " + selected.getName());
        popup.setScene(new javafx.scene.Scene(root, 620, 420));
        popup.showAndWait();
    }

    // ── Navigation ────────────────────────────────────────────

    @FXML
    private void goBack(ActionEvent event) {
        try {
            UIUtils.switchScene((Node) event.getSource(),
                "/com/pharmasystem/ui/Dashboard.fxml", "PhaRx", true);
        } catch (Exception e) {
            e.printStackTrace();
            UIUtils.showAlert(Alert.AlertType.ERROR, "Navigation Error", "Unable to return to dashboard.");
        }
    }

    // ── Shared popup builder helpers ─────────────────────────

    /**
     * Standard dark header bar used on every popup — matches the sidebar style
     * of the main tabs (Dashboard, Inventory, POS).
     */
    private HBox popupHeader(String title, String subtitle) {
        // Icon box — identical "Rx" badge used across all tab headers
        StackPane iconBox = new StackPane();
        iconBox.setStyle("-fx-background-color: #2563EB; -fx-background-radius: 7; "
            + "-fx-min-width:32; -fx-min-height:32; -fx-max-width:32; -fx-max-height:32; -fx-alignment:CENTER;");
        Label iconLbl = new Label("Rx");
        iconLbl.setStyle("-fx-text-fill:white; -fx-font-size:11px; -fx-font-weight:bold;");
        iconBox.getChildren().add(iconLbl);

        VBox text = new VBox(2,
            styledLabel(title, "white", 14, true),
            styledLabel(subtitle, "#64748B", 11, false));

        HBox header = new HBox(12, iconBox, text);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #0A1628; -fx-padding: 18 24 18 24;");
        return header;
    }

    private Label styledLabel(String text, String color, int size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + color + "; -fx-font-size: " + size + "px;"
            + (bold ? " -fx-font-weight: bold;" : ""));
        return l;
    }

    private Label fieldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px; "
            + "-fx-font-weight: bold; -fx-letter-spacing: 1.5;");
        return l;
    }

    private Label errorLabel() {
        Label l = new Label("");
        l.setStyle("-fx-text-fill: #EF4444; -fx-font-size: 12px;");
        l.setWrapText(true);
        return l;
    }

    private TextField styledTextField(String prompt) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.setMaxWidth(Double.MAX_VALUE);
        tf.getStyleClass().add("form-field");
        return tf;
    }

    private DatePicker styledDatePicker() {
        DatePicker dp = new DatePicker();
        dp.setPromptText("Select expiry date");
        dp.setMaxWidth(Double.MAX_VALUE);
        dp.getStyleClass().add("date-picker");
        return dp;
    }

    private Button primaryButton(String text) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("btn-primary");
        return b;
    }

    private Button dangerButton(String text) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("btn-danger");
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("btn-secondary");
        return b;
    }

    private VBox fieldGroup(Label label, Control field) {
        return new VBox(6, label, field);
    }

    private VBox sectionBox(Node... children) {
        VBox vb = new VBox(12, children);
        vb.setStyle("-fx-padding: 20 24 20 24; -fx-background-color: #F4F6FA;");
        return vb;
    }

    private Label infoBox(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-background-color: #EFF6FF; -fx-background-radius: 8; "
            + "-fx-border-color: #BFDBFE; -fx-border-width: 1; -fx-border-radius: 8; "
            + "-fx-padding: 10 14 10 14; -fx-text-fill: #1E40AF; -fx-font-size: 12px;");
        return l;
    }

    private Region separator() {
        Region r = new Region();
        r.setPrefHeight(1);
        r.setStyle("-fx-background-color: #E2E8F0;");
        return r;
    }

    private HBox tabBar(Button... tabs) {
        HBox bar = new HBox(0, tabs);
        bar.setStyle("-fx-background-color: #F8FAFC; "
            + "-fx-border-color: transparent transparent #E2E8F0 transparent; "
            + "-fx-border-width: 0 0 1 0; -fx-padding: 0 24 0 24;");
        return bar;
    }

    private void styleTab(Button b, boolean active) {
        b.setStyle(active
            ? "-fx-background-color: #FFFFFF; -fx-text-fill: #2563EB; -fx-font-weight: bold; "
              + "-fx-font-size: 12px; -fx-border-color: transparent transparent #2563EB transparent; "
              + "-fx-border-width: 0 0 2 0; -fx-background-radius: 0; -fx-padding: 10 20 10 20; -fx-cursor: hand;"
            : "-fx-background-color: transparent; -fx-text-fill: #64748B; -fx-font-size: 12px; "
              + "-fx-border-width: 0; -fx-background-radius: 0; -fx-padding: 10 20 10 20; -fx-cursor: hand;");
    }
}
