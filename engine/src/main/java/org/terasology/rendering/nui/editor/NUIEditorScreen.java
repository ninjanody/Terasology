/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering.nui.editor;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import org.codehaus.plexus.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.assets.ResourceUrn;
import org.terasology.assets.format.AssetDataFile;
import org.terasology.assets.management.AssetManager;
import org.terasology.input.Keyboard;
import org.terasology.input.device.KeyboardDevice;
import org.terasology.registry.In;
import org.terasology.rendering.nui.CoreScreenLayer;
import org.terasology.rendering.nui.UIWidget;
import org.terasology.rendering.nui.WidgetUtil;
import org.terasology.rendering.nui.asset.UIElement;
import org.terasology.rendering.nui.asset.UIFormat;
import org.terasology.rendering.nui.databinding.Binding;
import org.terasology.rendering.nui.events.NUIKeyEvent;
import org.terasology.rendering.nui.widgets.*;
import org.terasology.rendering.nui.widgets.models.JsonTree;
import org.terasology.rendering.nui.widgets.models.JsonTreeConverter;
import org.terasology.rendering.nui.widgets.models.JsonTreeNode;
import org.terasology.rendering.nui.widgets.models.Tree;

import java.awt.*;
import java.awt.datatransfer.*;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * NUI editor overlay - contains file selection & editing widgets.
 */
public class NUIEditorScreen extends CoreScreenLayer {
    private Logger logger = LoggerFactory.getLogger(NUIEditorScreen.class);

    public static final ResourceUrn ASSET_URI = new ResourceUrn("engine:nuiEditorScreen");

    @In
    private NUIEditorSystem nuiEditorSystem;

    @In
    private AssetManager assetManager;

    /**
     * The list of loaded {@link UIElement} asset {@link ResourceUrn}s.
     */
    private List<ResourceUrn> availableAssetList = Lists.newArrayList();
    /**
     * The dropdown containing a list of available asset {@link ResourceUrn}s.
     */
    private UIDropdownScrollable availableAssetDropdown;
    /**
     * The Urn of the currently selected UIElement.
     */
    private ResourceUrn selectedUrn;
    /**
     * The tree view containing a {@link JsonTree} representation of the asset being edited.
     */
    private UITreeView editorTreeView;
    /**
     * The {@link UIBox} containing the screen being edited.
     */
    private UIBox selectedScreenContainer;
    /**
     *
     */
    private List<JsonTree> editorHistory = Lists.newArrayList();
    /**
     *
     */
    private int editorHistoryPosition;

    @Override
    public void initialise() {
        // Fetch the interface widgets.
        availableAssetDropdown = find("availableAssets", UIDropdownScrollable.class);
        editorTreeView = find("editor", UITreeView.class);
        selectedScreenContainer = find("selectedScreen", UIBox.class);

        // Populate the asset dropdown with the asset list.
        availableAssetList.addAll(assetManager
                .getAvailableAssets(UIElement.class)
                .stream()
                .collect(Collectors.toList()));

        // Exclude the NUI editor itself, then sort the list.
        availableAssetList.removeIf(asset -> asset.getRootUrn().equals(ASSET_URI));
        availableAssetList.sort(Comparator.comparing(ResourceUrn::toString));

        availableAssetDropdown.setOptions(availableAssetList);
        availableAssetDropdown.bindSelection(new Binding<ResourceUrn>() {
            @Override
            public ResourceUrn get() {
                return selectedUrn;
            }

            @Override
            public void set(ResourceUrn value) {
                if (selectedUrn != value) {
                    selectFile(value);
                }
            }
        });

        // When the tree view is updated, update the widget accordingly.
        editorTreeView.subscribe(() -> {
            JsonTree tree = (JsonTree) (editorTreeView.getModel().getItem(0).getRoot());
            if (editorHistoryPosition < editorHistory.size() - 1) {
                editorHistory = editorHistory.subList(0, editorHistoryPosition + 1);
            }
            editorHistory.add(tree);
            editorHistoryPosition++;

            updateWidget(tree);
        });

        // Set the handler for the Copy button.
        WidgetUtil.trySubscribe(this, "copy", button -> {
            if (selectedUrn != null) {
                // Deserialize the tree view's internal JsonTree into the original JSON string.
                JsonElement json = JsonTreeConverter.deserialize(editorTreeView.getModel().getItem(0).getRoot());
                String jsonString = new GsonBuilder().setPrettyPrinting().create().toJson(json);

                // Set the clipboard contents to the JSON string.
                // ClipboardManager not used here to make the editor accessible within the main menu.
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                try {
                    clipboard.setContents(new StringSelection(jsonString), null);
                } catch (IllegalStateException e) {
                    logger.warn("Clipboard inaccessible.", e);
                }
            }
        });

        // Set the handler for the Paste button.
        WidgetUtil.trySubscribe(this, "paste", button -> {
            // Fetch the clipboard contents.
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable t = clipboard.getContents(null);

            String clipboardContents = null;
            try {
                if (t != null) {
                    clipboardContents = (String) t.getTransferData(DataFlavor.stringFlavor);
                }
            } catch (UnsupportedFlavorException | IOException e) {
                logger.warn("Could not fetch clipboard contents.", e);
            }

            if (clipboardContents != null) {
                try {
                    // If the clipboard contents are a valid JSON string, serialize them into a JsonTree.
                    JsonElement json = new JsonParser().parse(clipboardContents);
                    JsonTree tree = JsonTreeConverter.serialize(json);
                    updateTreeView(tree);
                    updateWidget(tree);
                } catch (JsonSyntaxException | NullPointerException e) {
                    logger.warn("Could not construct a valid tree from clipboard contents.", e);
                }
            }
        });

        // Set the handler for the Settings button.
        WidgetUtil.trySubscribe(this, "settings", button -> {
            getManager().pushScreen(NUIEditorSettingsPopup.ASSET_URI, NUIEditorSettingsPopup.class);
        });

        // Set the handlers for the Undo/Redo buttons.

        WidgetUtil.trySubscribe(this, "undo", button -> {
            undo();
        });

        WidgetUtil.trySubscribe(this, "redo", button -> {
            redo();
        });
    }

    private void updateTreeView(JsonTree tree) {
        Iterator it = tree.getDepthFirstIterator(false);
        while (it.hasNext()) {
            ((Tree<JsonTreeNode>) it.next()).setExpanded(true);
        }

        // Set the tree view's internal model to the resulting tree.
        editorTreeView.setModel(tree.copy());
    }

    private void updateWidget(JsonTree tree) {
        // Update the widget.
        UIWidget widget;
        try {
            JsonElement element = JsonTreeConverter.deserialize(tree);
            widget = new UIFormat().load(element).getRootWidget();
        } catch (Exception e) {
            widget = new UILabel(ExceptionUtils.getStackTrace(e));
        }
        selectedScreenContainer.setContent(widget);
    }

    private void undo() {
        if (editorHistoryPosition > 0) {
            editorHistoryPosition--;
            JsonTree tree = editorHistory.get(editorHistoryPosition);
            updateTreeView(tree);
            updateWidget(tree);
        }
    }

    private void redo() {
        if (editorHistoryPosition < editorHistory.size() - 1) {
            editorHistoryPosition++;
            JsonTree tree = editorHistory.get(editorHistoryPosition);
            updateTreeView(tree);
            updateWidget(tree);
        }
    }

    @Override
    public boolean isEscapeToCloseAllowed() {
        return false;
    }

    @Override
    public boolean onKeyEvent(NUIKeyEvent event) {
        if (event.isDown()) {
            int id = event.getKey().getId();
            KeyboardDevice keyboard = event.getKeyboard();
            boolean ctrlDown = keyboard.isKeyDown(Keyboard.KeyId.RIGHT_CTRL) || keyboard.isKeyDown(Keyboard.KeyId.LEFT_CTRL);

            if (id == Keyboard.KeyId.ESCAPE) {
                getAnimationSystem().stop();
                nuiEditorSystem.toggleEditor();
                return true;
            } else if (ctrlDown && id == Keyboard.KeyId.Z) {
                undo();
                return true;
            } else if (ctrlDown && id == Keyboard.KeyId.Y) {
                redo();
                return true;
            } else {
                return false;
            }
        }

        return false;
    }

    /**
     * @param urn The Urn of the file to be edited.
     */
    public void selectFile(ResourceUrn urn) {
        if (assetManager.getAsset(urn, UIElement.class).isPresent()) {
            UIElement element = assetManager.getAsset(urn, UIElement.class).get();

            // Fetch the file from the asset Urn.
            AssetDataFile source = element.getSource();

            String content = null;
            try (JsonReader reader = new JsonReader(new InputStreamReader(source.openStream(), Charsets.UTF_8))) {
                reader.setLenient(true);
                content = new JsonParser().parse(reader).toString();
            } catch (IOException e) {
                logger.error(String.format("Could not load asset source file for %s", urn.toString()), e);
            }

            if (content != null) {
                // Serialize the JSON string into a JsonTree and expand its' nodes.
                JsonTree tree = JsonTreeConverter.serialize(new JsonParser().parse(content));

                updateTreeView(tree);
                updateWidget(tree);

                editorHistory.clear();
                editorHistory.add(tree);
                editorHistoryPosition = 0;
            }
            selectedUrn = urn;
        }
    }
}